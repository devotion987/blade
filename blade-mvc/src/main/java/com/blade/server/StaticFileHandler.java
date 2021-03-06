package com.blade.server;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URLConnection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blade.Blade;
import com.blade.kit.DateKit;
import com.blade.kit.IOKit;
import com.blade.kit.StringKit;
import com.blade.mvc.Const;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

//import static io.netty.handler.codec.http.HttpHeaders.Names.*;

/**
 * @author biezhi 2017/5/31
 */
public class StaticFileHandler implements RequestHandler<Boolean> {

    public static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class);

    private boolean showFileList;

    public static final int HTTP_CACHE_SECONDS = 60;

    public StaticFileHandler(Blade blade) {
        this.showFileList = blade.environment().getBoolean(Const.ENV_KEY_STATIC_LIST, false);
    }

    /**
     * print static file to clinet
     */
    @Override
    public Boolean handle(ChannelHandlerContext ctx, Request request, Response response) throws Exception {
        if (!"GET".equals(request.method())) {
            sendError(ctx, METHOD_NOT_ALLOWED);
            return false;
        }

        String uri = request.uri();

        if (uri.startsWith(Const.WEB_JARS)) {
            InputStream input = StaticFileHandler.class.getResourceAsStream("/META-INF/resources" + uri);
            if (null == input) {
                sendError(ctx, NOT_FOUND);
            } else {
                if (http304(ctx, request, -1)) {
                    return false;
                }
                String content = IOKit.readToString(input);
                FullHttpResponse httpResponse = new DefaultFullHttpResponse(Const.HTTP_VERSION, OK,
                        Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
                setDateAndCacheHeaders(httpResponse, null);
                String contentType = StringKit.mimeType(uri);
                HttpHeaders headers = httpResponse.headers();
                if (null != contentType)
                    headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
                headers.set(HttpHeaderNames.CONTENT_LENGTH, content.length());
                if (request.keepAlive()) {
                    headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                }
                // Write the initial line and the header.
                ctx.writeAndFlush(httpResponse);
            }
            return false;
        }

        final String path = sanitizeUri(uri);
        if (path == null) {
            sendError(ctx, FORBIDDEN);
            return false;
        }

        File file = new File(path);
        if (file.isHidden() || !file.exists()) {
            sendError(ctx, NOT_FOUND);
            return false;
        }

        if (file.isDirectory() && showFileList) {
            if (uri.endsWith("/")) {
                sendListing(ctx, file, uri);
            } else {
                response.redirect(uri + '/');
            }
            return false;
        }

        if (!file.isFile()) {
            sendError(ctx, FORBIDDEN);
            return false;
        }

        // Cache Validation
        if (http304(ctx, request, file.lastModified())) {
            return false;
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            sendError(ctx, NOT_FOUND);
            return false;
        }

        long fileLength = raf.length();

        HttpResponse httpResponse = new DefaultHttpResponse(HTTP_1_1, OK);
        setContentTypeHeader(httpResponse, file);
        setDateAndCacheHeaders(httpResponse, file);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
        if (request.keepAlive()) {
            httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // Write the initial line and the header.
        ctx.write(httpResponse);

        // Write the content.
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        if (ctx.pipeline().get(SslHandler.class) == null) {
            sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength),
                    ctx.newProgressivePromise());
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        } else {
            sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                    ctx.newProgressivePromise());
            // HttpChunkedInput will write the end marker (LastHttpContent) for
            // us.
            lastContentFuture = sendFileFuture;
        }

        sendFileFuture.addListener(ProgressiveFutureListener.build(raf));

        // Decide whether to close the connection or not.
        if (!request.keepAlive()) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
        return false;
    }

    private boolean http304(ChannelHandlerContext ctx, Request request, long lastModified) {
        // Cache Validation
        String ifMdf = request.header(HttpHeaderNames.IF_MODIFIED_SINCE.toString());
        if (StringKit.isBlank(ifMdf)) {
            return false;
        }

        Date ifModifiedSinceDate = format(ifMdf, Const.HTTP_DATE_FORMAT);
        // Only compare up to the second because the datetime format we send to
        // the client
        // does not have milliseconds
        long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;

        if (lastModified < 0 && ifModifiedSinceDateSeconds <= Instant.now().getEpochSecond()) {
            sendNotModified(ctx);
            return true;
        }

        long fileLastModifiedSeconds = lastModified / 1000;
        if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
            sendNotModified(ctx);
            return true;
        }
        return false;
    }

    public Date format(String date, String pattern) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern, Locale.US);
        LocalDateTime formatted = LocalDateTime.parse(date, fmt);
        Instant instant = formatted.atZone(ZoneId.systemDefault()).toInstant();
        return Date.from(instant);
    }

    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[^-\\._]?[^<>&\\\"]*");

    private static void sendListing(ChannelHandlerContext ctx, File dir, String dirPath) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        StringBuilder buf = new StringBuilder().append("<!DOCTYPE html>\r\n")
                .append("<html><head><meta charset='utf-8' /><title>").append("文件列表: ").append(dirPath)
                .append("</title></head><body>\r\n").append("<h3>文件列表: ").append(dirPath).append("</h3>\r\n")
                .append("<ul>").append("<li><a href=\"../\">..</a></li>\r\n");

        for (File f : dir.listFiles()) {
            if (f.isHidden() || !f.canRead()) {
                continue;
            }
            String name = f.getName();
            if (!ALLOWED_FILE_NAME.matcher(name).matches()) {
                continue;
            }
            buf.append("<li><a href=\"").append(name).append("\">").append(name).append("</a></li>\r\n");
        }

        buf.append("</ul></body></html>\r\n");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        buffer.release();

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status,
                Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, Const.CONTENT_TYPE_TEXT);
        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * When file timestamp is the same as what the browser is sending up, send a
     * "304 Not Modified"
     *
     * @param ctx Context
     */
    private static void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        setDateHeader(response);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sets the Date header for the HTTP response
     *
     * @param response HTTP response
     */
    private static void setDateHeader(FullHttpResponse response) {
        response.headers().set(HttpHeaderNames.DATE, DateKit.gmtDate());
    }

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");

    private static String sanitizeUri(String uri) {
        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null;
        }
        // Convert file separators.
        uri = uri.replace('/', File.separatorChar);
        // Simplistic dumb security check.
        // You will have to do something serious in the production environment.
        if (uri.contains(File.separator + '.') || uri.contains('.' + File.separator) || uri.charAt(0) == '.'
                || uri.charAt(uri.length() - 1) == '.' || INSECURE_URI.matcher(uri).matches()) {
            return null;
        }
        // Convert to absolute path.
        return Const.CLASSPATH + uri.substring(1);
    }

    /**
     * Sets the Date and Cache headers for the HTTP Response
     *
     * @param response    HTTP response
     * @param fileToCache file to extract content type
     */
    private static void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
        // Date header
        LocalDateTime localTime = LocalDateTime.now();
        String date = DateKit.gmtDate(localTime);
        response.headers().set(HttpHeaderNames.DATE, date);
        String lastModifed = date;
        LocalDateTime newTime = localTime.plusSeconds(HTTP_CACHE_SECONDS);
        date = DateKit.gmtDate(newTime);

        // Add cache headers
        response.headers().set(HttpHeaderNames.EXPIRES, date);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        if (null != fileToCache) {
            lastModifed = DateKit.gmtDate(new Date(fileToCache.lastModified()));
        }
        response.headers().set(HttpHeaderNames.LAST_MODIFIED, lastModifed);
    }

    /**
     * Sets the content type header for the HTTP Response
     *
     * @param response HTTP response
     * @param file     file to extract content type
     */
    private static void setContentTypeHeader(HttpResponse response, File file) {
        String contentType = StringKit.mimeType(file.getName());
        if (null == contentType) {
            contentType = URLConnection.guessContentTypeFromName(file.getName());
        }
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

}