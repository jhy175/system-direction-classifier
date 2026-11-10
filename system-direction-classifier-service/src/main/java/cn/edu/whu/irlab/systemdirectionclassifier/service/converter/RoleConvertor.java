package cn.edu.whu.irlab.systemdirectionclassifier.service.converter;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.RoleRequestDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.RoleResponseDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject.RoleDO;

import java.util.List;

/**
 * 角色转换器
 *
 * @author <a href="mailto:shiyindaxiaojie@gmail.com">gyl</a>
 * @since 2.4.14
 */
@Mapper(componentModel = "spring",
	nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
	nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface RoleConvertor {

	RoleDO dtoToDataObject(RoleRequestDTO dto);

	void updateDataObjectFromDTO(RoleRequestDTO dto, @MappingTarget RoleDO dataObject);

	RoleResponseDTO dataObjectToDTO(RoleDO dataObject);

	List<RoleResponseDTO> dataObjectListToDTOList(List<RoleDO> dataObjectList);
}
