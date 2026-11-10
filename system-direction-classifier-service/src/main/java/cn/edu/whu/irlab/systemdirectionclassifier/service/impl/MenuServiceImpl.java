package cn.edu.whu.irlab.systemdirectionclassifier.service.impl;
import cn.edu.whu.irlab.systemdirectionclassifier.api.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ylzl.eden.spring.framework.dto.*;
import java.util.*;
@Service("menuService") @RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {
    private final IdentityLegacyAdapter adapter;
    @Override public Result createMenu(MenuRequestDTO dto) { adapter.identity.create("menus",adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result modifyMenu(Long id,MenuRequestDTO dto) { adapter.identity.update("menus",id,adapter.input(dto));return Result.buildSuccess(); }
    @Override public Result removeMenu(Long id) { adapter.identity.remove("menus",id);return Result.buildSuccess(); }
    @Override public SingleResult<MenuResponseDTO> getMenuById(Long id) { return SingleResult.build(adapter.convert(adapter.identity.get("menus",id),MenuResponseDTO.class)); }
    @Override public Result showMenu(Long id) { adapter.state("menus",id,1);return Result.buildSuccess(); }
    @Override public Result hideMenu(Long id) { adapter.state("menus",id,0);return Result.buildSuccess(); }
    @Override public MultiResult<MenuTreeDTO> getMenuTree() { return MultiResult.build(adapter.list(adapter.identity.tree("menus"),MenuTreeDTO.class)); }
}
