package cn.edu.whu.irlab.systemdirectionclassifier.api.dto;
import lombok.*;
import java.io.Serializable;
import com.fasterxml.jackson.databind.JsonNode;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode @ToString
public class UserRequestDTO implements Serializable {
    private String login;
    private String displayName;
    @ToString.Exclude private String password;
    private String email;
    private String phone;
    private String avatarUrl;
    private String organization;
    private String department;
    private Integer status;
    private String langKey;
    private String preferences;
    private String remark;
}
