package cn.edu.whu.irlab.systemdirectionclassifier.api;

import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import org.ylzl.eden.spring.framework.dto.PageResult;
import org.ylzl.eden.spring.framework.dto.Result;
import org.ylzl.eden.spring.framework.dto.SingleResult;

import java.util.List;

/**
 * 角色业务逻辑接口
 *
 * @author <a href="mailto:shiyindaxiaojie@gmail.com">gyl</a>
 * @since 2.4.14
 */
public interface RoleService {

	Result createRole(RoleRequestDTO dto);

	Result modifyRole(Long id, RoleRequestDTO dto);

	Result removeRole(Long id);

	Result enableRole(Long id);

	Result disableRole(Long id);

	SingleResult<RoleResponseDTO> getRoleById(Long id);

	PageResult<RoleResponseDTO> listRoleByPage(RolePageQuery query);

	Result assignPermissions(Long roleId, List<Long> permissionIds);

	Result assignMenus(Long roleId, List<Long> menuIds);

	MultiResult<PermissionResponseDTO> getRolePermissions(Long roleId);

	MultiResult<MenuTreeDTO> getRoleMenus(Long roleId);
}
