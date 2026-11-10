package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import cn.edu.whu.irlab.systemdirectionclassifier.api.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ylzl.eden.spring.framework.dto.*;
import java.util.*;
@Service("userService") @RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final IdentityLegacyAdapter adapter;
    @Override public Result createUser(UserRequestDTO dto) { adapter.identity.create("users",adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result modifyUser(Long id,UserRequestDTO dto) { adapter.identity.update("users",id,adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result removeUser(Long id) { adapter.identity.remove("users",id);return Result.buildSuccess(); }
    @Override public SingleResult<UserResponseDTO> getUserById(Long id) { return SingleResult.build(adapter.convert(adapter.identity.get("users",id),UserResponseDTO.class)); }
    @Override public PageResult<UserResponseDTO> listUserByPage(UserPageQuery query) { return adapter.page("users",query,UserResponseDTO.class); }
    @Override public Result activateUser(Long id) { adapter.state("users",id,1);return Result.buildSuccess(); }
    @Override public Result lockUser(Long id) { adapter.state("users",id,2);return Result.buildSuccess(); }
    @Override public Result unlockUser(Long id) { adapter.state("users",id,1);return Result.buildSuccess(); }
    @Override public Result disableUser(Long id) { adapter.state("users",id,0);return Result.buildSuccess(); }
    @Override public Result assignRoles(Long id,List<Long> ids) { adapter.identity.assign("users",id,"roles",ids);return Result.buildSuccess(); }
    @Override public MultiResult<RoleResponseDTO> getUserRoles(Long id) { return MultiResult.build(adapter.list(adapter.identity.relations("users",id,"roles"),RoleResponseDTO.class)); }
    @Override public MultiResult<PermissionResponseDTO> getUserPermissions(Long id) { return MultiResult.build(adapter.list(adapter.identity.relations("users",id,"permissions"),PermissionResponseDTO.class)); }
    @Override public MultiResult<MenuTreeDTO> getUserMenus(Long id) { return MultiResult.build(adapter.list(adapter.identity.relations("users",id,"menus"),MenuTreeDTO.class)); }
}
