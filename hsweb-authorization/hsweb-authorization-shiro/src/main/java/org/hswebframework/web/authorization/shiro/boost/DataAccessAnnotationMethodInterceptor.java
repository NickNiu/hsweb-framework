/*
 * Copyright 2016 http://www.hswebframework.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.hswebframework.web.authorization.shiro.boost;

import org.apache.shiro.aop.AnnotationResolver;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.aop.AuthorizingAnnotationHandler;
import org.apache.shiro.authz.aop.AuthorizingAnnotationMethodInterceptor;
import org.hswebframework.web.ApplicationContextHolder;
import org.hswebframework.web.AuthorizeException;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.access.DataAccessConfig;
import org.hswebframework.web.authorization.access.DataAccessController;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Logical;
import org.hswebframework.web.authorization.annotation.RequiresDataAccess;
import org.hswebframework.web.boost.aop.context.MethodInterceptorHolder;
import org.hswebframework.web.boost.aop.context.MethodInterceptorParamContext;
import org.hswebframework.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 数据级权限控制实现 <br>
 * 通过在方法上注解{@link RequiresDataAccess}，标识需要进行数据级权限控制<br>
 * 控制的方式和规则由 {@link Permission#getDataAccesses()}实现<br>
 *
 * @author zhouhao
 * @see DefaultDataAccessController
 * @see DataAccessAnnotationHandler#assertAuthorized(Annotation)
 * @since 3.0
 */
public class DataAccessAnnotationMethodInterceptor extends AuthorizingAnnotationMethodInterceptor {

    public DataAccessAnnotationMethodInterceptor(DataAccessController controller, AnnotationResolver resolver) {
        super(new DataAccessAnnotationHandler(controller), resolver);
    }

    private static final Logger logger = LoggerFactory.getLogger(DataAccessAnnotationMethodInterceptor.class);

    static class DataAccessAnnotationHandler extends AuthorizingAnnotationHandler {
        protected DataAccessController dataAccessController;

        public DataAccessAnnotationHandler(DataAccessController controller) {
            super(RequiresDataAccess.class);
            this.dataAccessController = controller;
        }

        final Map<Class<DataAccessController>, DataAccessController> cache = new HashMap<>(128);

        @Override
        public void assertAuthorized(Annotation a) throws AuthorizationException {
            if (!(a instanceof RequiresDataAccess)) return;
            MethodInterceptorHolder holder = MethodInterceptorHolder.current();
            if (null == holder) {
                logger.warn("MethodInterceptorHolder is null!");
                return;
            }
            //无权限信息
            Authentication authentication = Authentication.current().orElseThrow(AuthorizeException::new);
            RequiresDataAccess accessAnn = ((RequiresDataAccess) a);
            DataAccessController accessController = dataAccessController;
            //在注解上自定义的权限控制器
            if (DataAccessController.class != accessAnn.controllerClass()) {
                if (null == (accessController = cache.get(accessAnn.controllerClass()))) {
                    synchronized (cache) {
                        if (null == (accessController = cache.get(accessAnn.controllerClass())))
                            try {
                                accessController = accessAnn.controllerClass().newInstance();
                                cache.put(accessAnn.controllerClass(), accessController);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                    }
                }
            } else if (!StringUtils.isNullOrEmpty(accessAnn.controllerBeanName())) {
                //获取spring上下文中的控制器
                accessController = ApplicationContextHolder.get().getBean(accessAnn.controllerBeanName(), DataAccessController.class);
            }
            DataAccessController finalAccessController = accessController;
            Authorize classAnnotation = holder.findClassAnnotation(Authorize.class);
            Authorize methodAnnotation = holder.findMethodAnnotation(Authorize.class);
            Set<String> permissions = new HashSet<>();
            List<String> actionList = new ArrayList<>(Arrays.asList(accessAnn.action()));

            if (classAnnotation != null) {
                permissions.addAll(Arrays.asList(classAnnotation.permission()));
                if (actionList.isEmpty())
                    actionList.addAll(Arrays.asList(classAnnotation.action()));
            }
            if (methodAnnotation != null) {
                permissions.addAll(Arrays.asList(methodAnnotation.permission()));
                if (actionList.isEmpty())
                    actionList.addAll(Arrays.asList(methodAnnotation.action()));
            }

            String permission = accessAnn.permission();

            if ("".equals(permission)) {
                if (permissions.size() != 1) {
                    throw new IndexOutOfBoundsException("permission setting size must be 1");
                }
                permission = permissions.iterator().next();
            }
            MethodInterceptorParamContext context = holder.createParamContext();
            Permission permissionInfo = authentication.getPermission(permission).orElseThrow(AuthenticationException::new);

            //取得当前登录用户持有的控制规则
            Set<DataAccessConfig> accesses = permissionInfo
                    .getDataAccesses()
                    .stream()
                    .filter(access -> actionList.contains(access.getAction()))
                    .collect(Collectors.toSet());
            //无规则,则代表不进行控制
            if (accesses.isEmpty()) return;
            //单个规则验证函数
            Function<Predicate<DataAccessConfig>, Boolean> function =
                    accessAnn.logical() == Logical.AND ?
                            accesses.stream()::allMatch : accesses.stream()::anyMatch;
            //调用控制器进行验证
            boolean isAccess = function.apply(access -> finalAccessController.doAccess(access, context));
            if (!isAccess) {
                throw new AuthorizationException("{access_deny}");
            }
        }
    }
}
