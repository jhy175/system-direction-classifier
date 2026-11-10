package cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.io.Serializable;
import java.time.LocalDateTime;
@Data @NoArgsConstructor @AllArgsConstructor @SuperBuilder @EqualsAndHashCode @ToString @TableName("demo_user")
public class UserDO implements Serializable {
    @TableId(value="id",type=IdType.AUTO)
    private Long id;
    @TableField("login")
    private String login;
    @TableField("display_name")
    private String displayName;
    @TableField("password_hash") @ToString.Exclude
    private String passwordHash;
    @TableField("email")
    private String email;
    @TableField("phone")
    private String phone;
    @TableField("avatar_url")
    private String avatarUrl;
    @TableField("organization")
    private String organization;
    @TableField("department")
    private String department;
    @TableField("status")
    private Integer status;
    @TableField("lang_key")
    private String langKey;
    @TableField("preferences")
    private String preferences;
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;
    @TableField("last_login_ip")
    private String lastLoginIp;
    @TableField("remark")
    private String remark;
    @TableField("created_by")
    private Long createdBy;
    @TableField("updated_by")
    private Long updatedBy;
    @TableField("created_at")
    private LocalDateTime createdDate;
    @TableField("updated_at")
    private LocalDateTime lastModifiedDate;
}
