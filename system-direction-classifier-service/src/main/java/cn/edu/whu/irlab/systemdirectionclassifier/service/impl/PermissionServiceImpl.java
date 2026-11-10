package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import cn.edu.whu.irlab.systemdirectionclassifier.api.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ylzl.eden.spring.framework.dto.*;
import java.util.*;
@Service("permissionService") @RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {
    private final IdentityLegacyAdapter adapter;
    @Override public Result createPermission(PermissionRequestDTO dto) { adapter.identity.create("permissions",adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result modifyPermission(Long id,PermissionRequestDTO dto) { adapter.identity.update("permissions",id,adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result removePermission(Long id) { adapter.identity.remove("permissions",id);return Result.buildSuccess(); }
    @Override public SingleResult<PermissionResponseDTO> getPermissionById(Long id) { return SingleResult.build(adapter.convert(adapter.identity.get("permissions",id),PermissionResponseDTO.class)); }
    @Override public PageResult<PermissionResponseDTO> listPermissionByPage(PermissionPageQuery query) { return adapter.page("permissions",query,PermissionResponseDTO.class); }
    @Override public MultiResult<PermissionTreeDTO> getPermissionTree() { return MultiResult.build(adapter.list(adapter.identity.tree("permissions"),PermissionTreeDTO.class)); }
}
