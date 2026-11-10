package cn.edu.whu.irlab.systemdirectionclassifier.service.converter;

import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.UserRequestDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.UserResponseDTO;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject.UserDO;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * 用户转换器
 *
 * @author <a href="mailto:shiyindaxiaojie@gmail.com">gyl</a>
 * @since 2.4.13
 */
@Mapper(componentModel = "spring",
	nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
	nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface UserConvertor {

	UserDO dtoToDataObject(UserRequestDTO dto);

	void updateDataObjectFromDTO(UserRequestDTO dto, @MappingTarget UserDO dataObject);

	UserResponseDTO dataObjectToVO(UserDO dataObject);

	List<UserResponseDTO> dataObjectListToVOList(List<UserDO> dataObjectList);
}
