package cn.edu.whu.irlab.systemdirectionclassifier.service.converter;
import cn.edu.whu.irlab.systemdirectionclassifier.api.dto.*;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject.UserDO;
import org.mapstruct.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
@Mapper(componentModel="spring",nullValueCheckStrategy=NullValueCheckStrategy.ALWAYS,nullValuePropertyMappingStrategy=NullValuePropertyMappingStrategy.IGNORE)
public interface UserConvertor {
    @Mapping(target="passwordHash",ignore=true)
    UserDO dtoToDataObject(UserRequestDTO dto);
    @Mapping(target="passwordHash",ignore=true)
    void updateDataObjectFromDTO(UserRequestDTO dto,@MappingTarget UserDO dataObject);
    @Mapping(target="createdAt",source="createdDate") @Mapping(target="updatedAt",source="lastModifiedDate")
    UserResponseDTO dataObjectToVO(UserDO dataObject);
    List<UserResponseDTO> dataObjectListToVOList(List<UserDO> dataObjectList);
    default JsonNode preferences(String value) {
        if(value==null)return null;
        try{return new ObjectMapper().readTree(value);}
        catch(Exception e){throw new IllegalArgumentException("Invalid preferences",e);}
    }
}
