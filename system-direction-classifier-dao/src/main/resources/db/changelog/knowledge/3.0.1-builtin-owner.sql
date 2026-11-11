--liquibase formatted sql

--changeset irlab:knowledge-3.0.1-builtin-owner dbms:mysql
UPDATE demo_knowledge_base b
JOIN demo_user u ON u.login = 'admin' AND u.status = 1
JOIN demo_user_role ur ON ur.user_id = u.id
JOIN demo_role r ON r.id = ur.role_id AND r.code = 'ADMIN' AND r.status = 1
SET b.owner_user_id = u.id
WHERE b.builtin_key IN ('moe', 'wos_ssci', 'wos_scie')
  AND b.visibility = 'builtin' AND NOT (b.owner_user_id <=> u.id);
