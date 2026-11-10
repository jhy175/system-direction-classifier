/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.edu.whu.irlab.systemdirectionclassifier.service.rpc;

import org.apache.dubbo.config.annotation.DubboService;
import cn.edu.whu.irlab.systemdirectionclassifier.api.UserService;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.UserDAO;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.dao.RbacManager;
import cn.edu.whu.irlab.systemdirectionclassifier.service.constant.RPC;
import cn.edu.whu.irlab.systemdirectionclassifier.service.converter.MenuConvertor;
import cn.edu.whu.irlab.systemdirectionclassifier.service.converter.PermissionConvertor;
import cn.edu.whu.irlab.systemdirectionclassifier.service.converter.RoleConvertor;
import cn.edu.whu.irlab.systemdirectionclassifier.service.converter.UserConvertor;
import cn.edu.whu.irlab.systemdirectionclassifier.service.impl.UserServiceImpl;

/**
 * 用户业务 RPC服务端
 *
 * @author <a href="mailto:shiyindaxiaojie@gmail.com">gyl</a>
 * @since 2.4.13
 */
@DubboService(timeout = RPC.DEFAULT_TIMEOUT)
public class UserProvider extends UserServiceImpl implements UserService {

	public UserProvider(UserDAO userDAO, RbacManager rbacManager, UserConvertor userConvertor,
						RoleConvertor roleConvertor, PermissionConvertor permissionConvertor,
						MenuConvertor menuConvertor) {
		super(userDAO, rbacManager, userConvertor, roleConvertor, permissionConvertor, menuConvertor);
	}
}
