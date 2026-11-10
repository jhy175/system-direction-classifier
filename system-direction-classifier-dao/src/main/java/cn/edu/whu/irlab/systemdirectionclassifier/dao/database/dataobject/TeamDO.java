package cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @NoArgsConstructor @AllArgsConstructor @Builder @TableName("demo_team")
public class TeamDO {
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String name;
    @TableField("owner_user_id") private Long ownerUserId;
    private String description;
    private Integer status;
    @TableField("created_by") private Long createdBy;
    @TableField("updated_by") private Long updatedBy;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
