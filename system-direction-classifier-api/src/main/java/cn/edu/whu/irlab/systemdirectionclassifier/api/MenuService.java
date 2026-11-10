package cn.edu.whu.irlab.systemdirectionclassifier.api;

import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MenuRequestDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MenuResponseDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MenuTreeDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.MultiResult;
import org.ylzl.eden.spring.framework.dto.Result;
import org.ylzl.eden.spring.framework.dto.SingleResult;

/**
 * 菜单业务逻辑接口
 *
 * @author <a href="mailto:shiyindaxiaojie@gmail.com">gyl</a>
 * @since 2.4.14
 */
public interface MenuService {

	Result createMenu(MenuRequestDTO dto);

	Result modifyMenu(Long id, MenuRequestDTO dto);

	Result removeMenu(Long id);

	Result showMenu(Long id);

	Result hideMenu(Long id);

	SingleResult<MenuResponseDTO> getMenuById(Long id);

	MultiResult<MenuTreeDTO> getMenuTree();
}
