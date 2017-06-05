package com.blade.mvc.route;

import com.blade.BladeException;
import com.blade.ioc.annotation.Order;
import com.blade.kit.*;
import com.blade.mvc.hook.Invoker;
import com.blade.mvc.hook.WebHook;
import com.blade.mvc.http.HttpMethod;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Default Route Matcher
 *
 * @author <a href="mailto:biezhi.me@gmail.com" target="_blank">biezhi</a>
 * @since 1.7.1-release
 */
public class RouteMatcher {

    private static final Logger log = LoggerFactory.getLogger(RouteMatcher.class);

    private static final String METHOD_NAME = "handle";

    // Storage URL and route
    private Map<String, Route> routes = new HashMap<>();
    private Map<String, List<Route>> hooks = new HashMap<>();
    private List<Route> middlewares;

    private Map<String, Method[]> classMethosPool = new ConcurrentHashMap<>();
    private Map<Class<?>, Object> controllerPool = new ConcurrentHashMap<>();

    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile(":(\\w+)");
    private static final String PATH_VARIABLE_REPLACE = "([^/]+)";

    private Map<HttpMethod, Map<Integer, FastRouteMappingInfo>> regexRoutes = new HashMap<>();

    private Map<String, Route> staticRoutes = new HashMap<>();
    private Map<HttpMethod, Pattern> regexRoutePatterns = new HashMap<>();
    private Map<HttpMethod, Integer> indexes = new HashMap<>();
    private Map<HttpMethod, StringBuilder> patternBuilders = new HashMap<>();

    public void addRoute(Route route) {
        String path = route.getPath();
        HttpMethod httpMethod = route.getHttpMethod();
        String key = path + "#" + httpMethod.toString();

        // exist
        if (this.routes.containsKey(key)) {
            log.warn("\tRoute {} -> {} has exist", path, httpMethod.toString());
        }

        if (BladeKit.isWebHook(httpMethod)) {
            Order order = route.getTargetType().getAnnotation(Order.class);
            if (null != order) {
                route.setSort(order.value());
            }
            if (this.hooks.containsKey(key)) {
                this.hooks.get(key).add(route);
            } else {
                List<Route> empty = new ArrayList<>();
                empty.add(route);
                this.hooks.put(key, empty);
            }
            log.debug("Add hook  => {}", route);
        } else {
            this.routes.put(key, route);
            log.debug("Add Route => {}", route);
        }
    }

    public void addRoutes(List<Route> routes) {
        routes.forEach(this::addRoute);
    }

    public Route addRoute(HttpMethod httpMethod, String path, RouteHandler handler, String methodName) throws NoSuchMethodException {
        Class<?> handleType = handler.getClass();
        Method method = handleType.getMethod(methodName, Request.class, Response.class);
        return addRoute(httpMethod, path, handler, RouteHandler.class, method);
    }


    public Route addRoute(HttpMethod httpMethod, String path, Object controller, Class<?> controllerType, Method method) {

        // [/** | /*]
        path = "*".equals(path) ? "/.*" : path;
        path = path.replace("/**", "/.*").replace("/*", "/.*");

        String key = path + "#" + httpMethod.toString();

        // exist
        if (this.routes.containsKey(key)) {
            log.warn("\tRoute {} -> {} has exist", path, httpMethod.toString());
        }

        Route route = new Route(httpMethod, path, controller, controllerType, method);
        if (BladeKit.isWebHook(httpMethod)) {
            Order order = controllerType.getAnnotation(Order.class);
            if (null != order) {
                route.setSort(order.value());
            }
            if (this.hooks.containsKey(key)) {
                this.hooks.get(key).add(route);
            } else {
                List<Route> empty = new ArrayList<>();
                empty.add(route);
                this.hooks.put(key, empty);
            }
        } else {
            this.routes.put(key, route);
        }
        return route;
    }

    public Route addRoute(String path, RouteHandler handler, HttpMethod httpMethod) {
        try {
            return addRoute(httpMethod, path, handler, METHOD_NAME);
        } catch (Exception e) {
            log.error("", e);
            return null;
        }
    }

    public void addRoute(String[] paths, RouteHandler handler, HttpMethod httpMethod) {
        for (String path : paths) {
            addRoute(path, handler, httpMethod);
        }
    }

    public void route(String path, Class<?> clazz, String methodName) {
        Assert.notNull(methodName, "Method name not is null");
        HttpMethod httpMethod = HttpMethod.ALL;
        if (methodName.contains(":")) {
            String[] methodArr = methodName.split(":");
            httpMethod = HttpMethod.valueOf(methodArr[0].toUpperCase());
            methodName = methodArr[1];
        }
        this.route(path, clazz, methodName, httpMethod);
    }

    public void route(String path, Class<?> clazz, String methodName, HttpMethod httpMethod) {
        try {
            Assert.notNull(path, "Route path not is null!");
            Assert.notNull(clazz, "Class EventType not is null!");
            Assert.notNull(methodName, "Method name not is null");
            Assert.notNull(httpMethod, "Request Method not is null");

            Method[] methods = classMethosPool.get(clazz.getName());
            if (null == methods) {
                methods = clazz.getMethods();
                classMethosPool.put(clazz.getName(), methods);
            }
            if (null != methods) {
                for (Method method : methods) {
                    if (method.getName().equals(methodName)) {
                        Object controller = controllerPool.get(clazz);
                        if (null == controller) {
                            controller = ReflectKit.newInstance(clazz);
                            controllerPool.put(clazz, controller);
                        }
                        addRoute(httpMethod, path, controller, clazz, method);
                    }
                }
            }
        } catch (Exception e) {
            log.error("", e);
        }
    }

    public void buildRoute(String path, Class<?> clazz, Method method, HttpMethod httpMethod) {
        addRoute(httpMethod, path, null, clazz, method);
    }

    public void delRoute(String key) {
        if (StringKit.isNotBlank(key)) {
            routes.remove(key);
        }
    }

    public Route lookupRoute(String httpMethod, String path) {
        path = parsePath(path);
        String routeKey = path + '#' + httpMethod.toUpperCase();
        Route route = staticRoutes.get(routeKey);
        if (null != route) {
            return route;
        }
        route = staticRoutes.get(path + "#ALL");
        if (null != route) {
            return route;
        }

        Map<String, String> uriVariables = new LinkedHashMap<>();
        HttpMethod requestMethod = HttpMethod.valueOf(httpMethod);
        try {
            Pattern pattern = regexRoutePatterns.get(requestMethod);
            if (null == pattern) {
                return null;
            }
            Matcher matcher = pattern.matcher(path);
            boolean matched = matcher.matches();
            if (!matched) {
                requestMethod = HttpMethod.ALL;
                pattern = regexRoutePatterns.get(requestMethod);
                if (null == pattern) {
                    return null;
                }
                matcher = pattern.matcher(path);
                matched = matcher.matches();
            }
            if (matched) {
                int i;
                for (i = 1; matcher.group(i) == null; i++) ;
                FastRouteMappingInfo mappingInfo = regexRoutes.get(requestMethod).get(i);
                route = mappingInfo.getRoute();

                // find path variable
                String uriVariable;
                int j = 0;
                while (++i <= matcher.groupCount() && (uriVariable = matcher.group(i)) != null) {
                    uriVariables.put(mappingInfo.getVariableNames().get(j++), uriVariable);
                }
                route.setPathParams(uriVariables);
                log.trace("lookup path: " + path + " uri variables: " + uriVariables);
            }
            return route;
        } catch (Exception e) {
            throw new BladeException(e);
        }
    }

    /**
     * Find all in before of the hook
     *
     * @param path request path
     */
    public List<Route> getBefore(String path) {
        String cleanPath = parsePath(path);
        List<Route> befores = hooks.values().stream()
                .flatMap(routes -> routes.stream())
                .sorted(Comparator.comparingInt(route -> route.getSort()))
                .filter(route -> {
                    return route.getHttpMethod() == HttpMethod.BEFORE && matchesPath(route.getPath(), cleanPath);
                })
                .collect(Collectors.toList());

        this.giveMatch(path, befores);
        return befores;
    }

    public List<Route> getBefore2(String path) {
        String cleanPath = parsePath(path);

        List<Route> befores = hooks.values().parallelStream()
                .flatMap(routes -> routes.stream())
                .sorted(Comparator.comparingInt(route -> route.getSort()))
                .filter(route -> route.getHttpMethod() == HttpMethod.BEFORE && matchesPath(route.getPath(), cleanPath))
                .collect(Collectors.toList());

        this.giveMatch(path, befores);
        return befores;
    }

    /**
     * Find all in after of the hooks
     *
     * @param path request path
     */
    public List<Route> getAfter(String path) {
        String cleanPath = parsePath(path);

        List<Route> afters = hooks.values().stream()
                .flatMap(routes -> routes.stream())
                .sorted(Comparator.comparingInt(route -> route.getSort()))
                .filter(route -> route.getHttpMethod() == HttpMethod.AFTER && matchesPath(route.getPath(), cleanPath))
                .collect(Collectors.toList());

        this.giveMatch(path, afters);
        return afters;
    }

    public List<Route> getMiddlewares() {
        return this.middlewares;
    }

    /**
     * Sort of path
     *
     * @param uri    request uri
     * @param routes route list
     */
    private void giveMatch(final String uri, List<Route> routes) {
        routes.stream().sorted((o1, o2) -> {
            if (o2.getPath().equals(uri)) {
                return o2.getPath().indexOf(uri);
            }
            return -1;
        });
    }

    /**
     * Matching path
     *
     * @param routePath   route path
     * @param pathToMatch match path
     * @return return match is success
     */
    private boolean matchesPath(String routePath, String pathToMatch) {
        routePath = routePath.replaceAll(PathKit.VAR_REGEXP, PathKit.VAR_REPLACE);
        return pathToMatch.matches("(?i)" + routePath);
    }

    /**
     * Parse PathKit
     *
     * @param path route path
     * @return return parsed path
     */
    private String parsePath(String path) {
        path = PathKit.fixPath(path);
        try {
            URI uri = new URI(path);
            return uri.getPath();
        } catch (URISyntaxException e) {
            log.error("parse [" + path + "] error", e);
            return null;
        }
    }

    // a bad way
    public void register() {
        routes.values().forEach(route -> log.info("Add route => {}", route));
        hooks.values().forEach(route -> log.info("Add hook  => {}", route));

        List<Route> routeHandlers = new ArrayList<>(routes.values());
        routeHandlers.addAll(hooks.values().stream().findAny().orElse(new ArrayList<>()));

        Stream.of(routes.values(), hooks.values().stream().findAny().orElse(new ArrayList<>()))
                .flatMap(c -> c.stream()).forEach(this::registerRoute);

        patternBuilders.keySet().stream()
                .filter(BladeKit::notIsWebHook)
                .forEach(httpMethod -> {
                    StringBuilder patternBuilder = patternBuilders.get(httpMethod);
                    if (patternBuilder.length() > 1) {
                        patternBuilder.setCharAt(patternBuilder.length() - 1, '$');
                    }
                    log.debug("Fast Route Method: {}, regex: {}", httpMethod, patternBuilder);
                    regexRoutePatterns.put(httpMethod, Pattern.compile(patternBuilder.toString()));
                });
    }

    public void registerRoute(Route route) {
        String path = parsePath(route.getPath());
        Matcher matcher = PATH_VARIABLE_PATTERN.matcher(path);
        boolean find = false;
        List<String> uriVariableNames = new ArrayList<>();
        while (matcher.find()) {
            if (!find) {
                find = true;
            }
            String group = matcher.group(0);
            uriVariableNames.add(group.substring(1));   // {id} -> id
        }
        HttpMethod httpMethod = route.getHttpMethod();
        if (find || BladeKit.isWebHook(httpMethod)) {
            if (regexRoutes.get(httpMethod) == null) {
                regexRoutes.put(httpMethod, new HashMap<>());
                patternBuilders.put(httpMethod, new StringBuilder("^"));
                indexes.put(httpMethod, 1);
            }
            int i = indexes.get(httpMethod);
            regexRoutes.get(httpMethod).put(i, new FastRouteMappingInfo(route, uriVariableNames));
            indexes.put(httpMethod, i + uriVariableNames.size() + 1);
            patternBuilders.get(httpMethod).append("(").append(matcher.replaceAll(PATH_VARIABLE_REPLACE)).append(")|");
        } else {
            String routeKey = path + '#' + httpMethod.toString();
            if (staticRoutes.get(routeKey) == null) {
                staticRoutes.put(routeKey, route);
            }
        }
    }

    public void clear() {
        this.routes.clear();
        this.hooks.clear();
        this.classMethosPool.clear();
        this.controllerPool.clear();
        this.regexRoutePatterns.clear();
        this.staticRoutes.clear();
        this.regexRoutes.clear();
        this.indexes.clear();
        this.patternBuilders.clear();
    }

    public void initMiddlewares(List<WebHook> hooks) {
        this.middlewares = hooks.stream().map(webHook -> {
            Method method = ReflectKit.getMethod(WebHook.class, "before", Invoker.class);
            return new Route(HttpMethod.BEFORE, "/.*", webHook, WebHook.class, method);
        }).collect(Collectors.toList());
    }

    private class FastRouteMappingInfo {
        Route route;
        List<String> variableNames;

        public FastRouteMappingInfo(Route route, List<String> variableNames) {
            this.route = route;
            this.variableNames = variableNames;
        }

        public Route getRoute() {
            return route;
        }

        public List<String> getVariableNames() {
            return variableNames;
        }
    }

}