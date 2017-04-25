/**
 * Copyright (c) 2015, biezhi 王爵 (biezhi.me@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blade.mvc.dispatch;

import com.blade.Blade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Blade Abstract DispatcherServlet
 *
 * @author <a href="mailto:biezhi.me@gmail.com" target="_blank">biezhi</a>
 * @since 1.7.1-release
 */
public abstract class AbsDispatcherServlet extends HttpServlet {

	private static final long serialVersionUID = -3071777104131316365L;

	private static final Logger LOGGER = LoggerFactory.getLogger(AbsDispatcherServlet.class);

	protected static ThreadPoolExecutor executor;
	protected Blade blade;
	protected DispatcherHandler dispatcherHandler;
	protected int asyncContextTimeout;

	@Override
	public void init(ServletConfig config) throws ServletException {
		blade = Blade.$();
		this.dispatcherHandler = new DispatcherHandler(blade.routers());
		this.asyncContextTimeout = blade.config().getInt("server.async-ctx-timeout", 10 * 1000);
		executor = new ThreadPoolExecutor(100, 200, 50000L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100));
		LOGGER.info("init worker thread pool.");
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		this.service(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		this.service(req, resp);
	}

	@Override
	public void destroy() {
		super.destroy();
		if (null != executor) {
			executor.shutdown();
			LOGGER.info("shutdown worker thread pool.");
		}
	}
}