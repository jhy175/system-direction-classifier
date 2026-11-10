package cn.edu.whu.irlab.systemdirectionclassifier.dao.database.dataobject;
import lombok.*;
import java.time.LocalDateTime;
/** Composite identity (teamId,userId) is handled explicitly by IdentityDAO, never a single-ID mapper. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamMemberDO {
    private Long teamId;
    private Long userId;
    private String memberRole;
    private Integer status;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
