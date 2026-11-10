package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import cn.edu.whu.irlab.systemdirectionclassifier.api.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ylzl.eden.spring.framework.dto.*;
import java.util.*;
@Service("roleService") @RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    private final IdentityLegacyAdapter adapter;
    @Override public Result createRole(RoleRequestDTO dto) { adapter.identity.create("roles",adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result modifyRole(Long id,RoleRequestDTO dto) { adapter.identity.update("roles",id,adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result removeRole(Long id) { adapter.identity.remove("roles",id);return Result.buildSuccess(); }
    @Override public SingleResult<RoleResponseDTO> getRoleById(Long id) { return SingleResult.build(adapter.convert(adapter.identity.get("roles",id),RoleResponseDTO.class)); }
    @Override public PageResult<RoleResponseDTO> listRoleByPage(RolePageQuery query) { return adapter.page("roles",query,RoleResponseDTO.class); }
    @Override public Result enableRole(Long id) { adapter.state("roles",id,1);return Result.buildSuccess(); }
    @Override public Result disableRole(Long id) { adapter.state("roles",id,0);return Result.buildSuccess(); }
    @Override public Result assignPermissions(Long id,List<Long> ids) { adapter.identity.assign("roles",id,"permissions",ids);return Result.buildSuccess(); }
    @Override public Result assignMenus(Long id,List<Long> ids) { adapter.identity.assign("roles",id,"menus",ids);return Result.buildSuccess(); }
    @Override public MultiResult<PermissionResponseDTO> getRolePermissions(Long id) { return MultiResult.build(adapter.list(adapter.identity.relations("roles",id,"permissions"),PermissionResponseDTO.class)); }
    @Override public MultiResult<MenuTreeDTO> getRoleMenus(Long id) { return MultiResult.build(adapter.list(adapter.identity.relations("roles",id,"menus"),MenuTreeDTO.class)); }
}
