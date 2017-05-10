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
package com.blade.mvc.annotation;

import com.blade.mvc.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Methods defined on the route notes
 * <p>
 * Restful routes:
 * <pre>
 * ==========================================================================================
 * verb    path                   action          used for
 * ==========================================================================================
 * GET     /users                 index 	       display a list of all books
 * GET     /users/new_form        new_form        return an HTML form for creating a new book
 * POST    /users                 create 	       create a new book
 * GET     /users/id              show            display a specific book
 * GET     /users/id/edit_form    edit_form       return an HTML form for editing a books
 * PUT     /users/id              update          update a specific book
 * DELETE 	/users/id              destroy         delete a specific book
 * </pre>
 *
 * @author <a href="mailto:biezhi.me@gmail.com" target="_blank">biezhi</a>
 * @since 1.5
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Route {

    /**
     * @return Request url
     */
    String[] value() default {"/"};

    /**
     * @return Request HttpMethod
     */
    HttpMethod method() default HttpMethod.ALL;


}