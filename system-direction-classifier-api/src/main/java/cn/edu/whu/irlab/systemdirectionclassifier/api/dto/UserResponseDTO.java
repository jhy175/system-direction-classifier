package cn.edu.whu.irlab.systemdirectionclassifier.api.dto;
import lombok.*;
import java.io.Serializable;
import com.fasterxml.jackson.databind.JsonNode;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode @ToString
public class UserResponseDTO implements Serializable {
    private Long id;
    private String login;
    private String displayName;
    private String email;
    private String phone;
    private String avatarUrl;
    private String organization;
    private String department;
    private Integer status;
    private String langKey;
    private JsonNode preferences;
    private String remark;
    private String lastLoginAt;
    private String lastLoginIp;
    private Long createdBy;
    private Long updatedBy;
    private String createdAt;
    private String updatedAt;
}
