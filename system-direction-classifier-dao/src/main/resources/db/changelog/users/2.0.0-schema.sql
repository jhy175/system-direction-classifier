-- 用户与权限模块设计稿，适用于 MySQL 8.0。
-- 用于空库评审和初始化，不是现有 demo_* 表的升级脚本。
-- 不使用 IF NOT EXISTS：已存在同名表时应报错，避免掩盖结构差异。
-- 不创建数据库、不插入初始账户、不接入自动迁移。
-- 普通业务字段允许 NULL；主键、关键标识和构成关系的 ID 必填。
-- 不声明物理外键，引用存在性和关联操作由服务层事务校验。

CREATE TABLE demo_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    login VARCHAR(64) NOT NULL COMMENT '登录账号，应用层去首尾空格并统一大小写',
    display_name VARCHAR(100) NULL DEFAULT NULL COMMENT '显示名称，未填写时显示登录账号',
    password_hash VARCHAR(255) NULL DEFAULT NULL COMMENT '密码哈希，可空以支持未设置本地密码的账户',
    email VARCHAR(254) NULL DEFAULT NULL COMMENT '联系邮箱；填写时唯一，空字符串转 NULL',
    phone VARCHAR(32) NULL DEFAULT NULL COMMENT '联系电话，当前不作为登录标识',
    avatar_url VARCHAR(1024) NULL DEFAULT NULL COMMENT '头像地址或文件引用',
    organization VARCHAR(200) NULL DEFAULT NULL COMMENT '所属单位，仅用于用户资料',
    department VARCHAR(200) NULL DEFAULT NULL COMMENT '所属部门，仅用于用户资料',
    status TINYINT NULL DEFAULT 0 COMMENT '0禁用或待启用，1启用，2锁定；NULL不允许登录',
    lang_key VARCHAR(20) NULL DEFAULT NULL COMMENT '语言偏好，例如 zh-CN',
    preferences JSON NULL DEFAULT NULL COMMENT '非敏感界面偏好，例如是否展开高级配置',
    last_login_at DATETIME NULL DEFAULT NULL COMMENT '最近成功登录时间',
    last_login_ip VARCHAR(45) NULL DEFAULT NULL COMMENT '最近成功登录IP，支持IPv6',
    remark VARCHAR(1000) NULL DEFAULT NULL COMMENT '备注',
    created_by BIGINT NULL DEFAULT NULL COMMENT '创建人用户ID，系统导入可空',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_login (login),
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户与资料';

CREATE TABLE demo_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '系统角色主键',
    code VARCHAR(64) NOT NULL COMMENT '稳定角色编码，例如 ADMIN、USER',
    name VARCHAR(100) NOT NULL COMMENT '角色名称',
    description VARCHAR(1000) NULL DEFAULT NULL COMMENT '角色说明',
    status TINYINT NULL DEFAULT 1 COMMENT '0禁用，1启用；NULL不参与授权',
    sort INT NULL DEFAULT 0 COMMENT '显示顺序',
    created_by BIGINT NULL DEFAULT NULL COMMENT '创建人用户ID',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code),
    KEY idx_role_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统功能角色';

CREATE TABLE demo_permission (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限主键',
    code VARCHAR(128) NOT NULL COMMENT '稳定权限编码，例如 method:version:create',
    name VARCHAR(100) NOT NULL COMMENT '权限名称',
    type TINYINT NULL DEFAULT NULL COMMENT '1页面，2操作；仅用于分类，不替代后端鉴权',
    parent_id BIGINT NULL DEFAULT NULL COMMENT '父权限ID，根节点为NULL',
    description VARCHAR(1000) NULL DEFAULT NULL COMMENT '权限说明',
    status TINYINT NULL DEFAULT 1 COMMENT '0禁用，1启用；NULL不参与授权',
    sort INT NULL DEFAULT 0 COMMENT '显示顺序',
    created_by BIGINT NULL DEFAULT NULL COMMENT '创建人用户ID',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code),
    KEY idx_permission_parent (parent_id),
    KEY idx_permission_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后端功能权限目录';

CREATE TABLE demo_menu (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '菜单主键',
    name VARCHAR(100) NOT NULL COMMENT '菜单名称',
    path VARCHAR(255) NULL DEFAULT NULL COMMENT '前端路由；纯目录可空，填写时唯一',
    icon VARCHAR(100) NULL DEFAULT NULL COMMENT '菜单图标',
    parent_id BIGINT NULL DEFAULT NULL COMMENT '父菜单ID，根节点为NULL',
    component VARCHAR(255) NULL DEFAULT NULL COMMENT '前端组件标识',
    sort INT NULL DEFAULT 0 COMMENT '显示顺序',
    status TINYINT NULL DEFAULT 1 COMMENT '0隐藏，1展示；NULL不展示',
    created_by BIGINT NULL DEFAULT NULL COMMENT '创建人用户ID',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_path (path),
    KEY idx_menu_parent (parent_id),
    KEY idx_menu_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='前端导航菜单，不作为后端授权依据';

CREATE TABLE demo_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '系统角色ID',
    created_by BIGINT NULL DEFAULT NULL COMMENT '授权人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户与系统角色关系，删除记录表示撤销';

CREATE TABLE demo_role_permission (
    role_id BIGINT NOT NULL COMMENT '系统角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    created_by BIGINT NULL DEFAULT NULL COMMENT '授权人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色与功能权限关系，删除记录表示撤销';

CREATE TABLE demo_role_menu (
    role_id BIGINT NOT NULL COMMENT '系统角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    created_by BIGINT NULL DEFAULT NULL COMMENT '配置人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置时间',
    PRIMARY KEY (role_id, menu_id),
    KEY idx_role_menu_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色与可见菜单关系';

CREATE TABLE demo_team (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '团队主键',
    name VARCHAR(100) NOT NULL COMMENT '团队名称，允许不同团队重名',
    owner_user_id BIGINT NOT NULL COMMENT '团队负责人用户ID，须为有效团队成员',
    description VARCHAR(1000) NULL DEFAULT NULL COMMENT '团队说明',
    status TINYINT NULL DEFAULT 1 COMMENT '0停用，1启用；NULL不参与团队共享授权',
    created_by BIGINT NULL DEFAULT NULL COMMENT '创建人用户ID',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    KEY idx_team_owner (owner_user_id),
    KEY idx_team_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源共享团队，与用户单位资料独立';

CREATE TABLE demo_team_member (
    team_id BIGINT NOT NULL COMMENT '团队ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    member_role VARCHAR(32) NULL DEFAULT 'member' COMMENT 'admin团队管理员，member普通成员；负责人由团队表判定',
    status TINYINT NULL DEFAULT 1 COMMENT '0已退出或停用，1有效；NULL或未知成员角色不参与团队共享授权',
    joined_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近加入时间，重新加入时更新',
    left_at DATETIME NULL DEFAULT NULL COMMENT '最近退出时间，重新加入时清空',
    created_by BIGINT NULL DEFAULT NULL COMMENT '添加人用户ID',
    updated_by BIGINT NULL DEFAULT NULL COMMENT '修改人用户ID',
    created_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关系首次创建时间',
    updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (team_id, user_id),
    KEY idx_team_member_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队成员当前关系，支持一人加入多个团队';
