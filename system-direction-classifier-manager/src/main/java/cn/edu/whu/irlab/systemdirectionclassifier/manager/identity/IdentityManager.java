package cn.edu.whu.irlab.systemdirectionclassifier.manager.identity;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.*;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Identity aggregate rules. All write entry points run inside the service transaction. */
@Component
@RequiredArgsConstructor
public class IdentityManager {
    private final IdentityDAO db;
    private final ObjectMapper json;
    private static final Map<String,String> TABLES=new LinkedHashMap<>();
    private static final Map<String,Map<String,Integer>> FIELDS=new HashMap<>();
    private static final Map<String,String> MANAGE=new HashMap<>();
    private static final Set<String> RESERVED_ROLES=new HashSet<>(Arrays.asList("ADMIN","USER"));
    private static final List<String> ADMIN_PERMISSIONS=Arrays.asList(
        "user:manage","role:manage","permission:manage","menu:manage","team:manage","team:create");
    private static final List<String> USER_PERMISSIONS=Arrays.asList(
        "team:create","method:read","method:create","method:edit","method:version:create",
        "knowledge:read","knowledge:edit","knowledge:version:create","debug:run","debug:history:read",
        "batch:create","batch:read","batch:export","batch:retry","connection:use","connection:manage");
    static {
        TABLES.put("users","demo_user");TABLES.put("roles","demo_role");
        TABLES.put("permissions","demo_permission");TABLES.put("menus","demo_menu");TABLES.put("teams","demo_team");
        MANAGE.put("users","user:manage");MANAGE.put("roles","role:manage");
        MANAGE.put("permissions","permission:manage");MANAGE.put("menus","menu:manage");MANAGE.put("teams","team:manage");
        FIELDS.put("users",fields("login",64,"displayName",100,"email",254,"phone",32,"avatarUrl",1024,
            "organization",200,"department",200,"status",-1,"langKey",20,"preferences",-3,"remark",1000));
        FIELDS.put("roles",fields("code",64,"name",100,"description",1000,"status",-1,"sort",-1));
        FIELDS.put("permissions",fields("code",128,"name",100,"type",-1,"parentId",-2,
            "description",1000,"status",-1,"sort",-1));
        FIELDS.put("menus",fields("name",100,"path",255,"icon",100,"parentId",-2,"component",255,"sort",-1,"status",-1));
        FIELDS.put("teams",fields("name",100,"ownerUserId",-2,"description",1000,"status",-1));
    }
    private static Map<String,Integer> fields(Object...args) {
        Map<String,Integer> map=new LinkedHashMap<>();
        for(int i=0;i<args.length;i+=2)map.put((String)args[i],(Integer)args[i+1]);return map;
    }
    private String table(String resource) {
        String table=TABLES.get(resource);if(table==null)throw error(404,"未知资源");return table;
    }
    private IdentityException error(int status,String message) { return new IdentityException(status,message); }
    private Long number(Object value) { return value==null?null:((Number)value).longValue(); }
    private boolean active(Map<String,Object> row) { return row!=null && Objects.equals(number(row.get("status")),1L); }
    private Long actor() { Long id=CurrentActor.id();requireActiveUser(id);return id; }
    private Map<String,Object> raw(String resource,Long id,boolean lock) {
        if(id==null || id<=0)throw error(400,"ID必须为正整数");
        Map<String,Object> row=db.find(table(resource),id,lock);
        if(row==null)throw error(404,"记录不存在");return row;
    }
    public Map<String,Object> requireActiveUser(Long id) {
        Map<String,Object> user=raw("users",id,false);
        if(!active(user))throw error(401,"账号已停用或锁定，请重新登录");return user;
    }
    private void validTargetUser(Long id) {
        if(!active(raw("users",id,true)))throw error(409,"目标用户必须处于启用状态");
    }
    private Map<String,Object> safe(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>(row);result.remove("passwordHash");return result;
    }
    private List<Map<String,Object>> effectiveRoles(Long userId) {
        return db.query("SELECT r.* FROM demo_role r JOIN demo_user_role ur ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 ORDER BY r.sort,r.id",userId);
    }
    public List<Map<String,Object>> effectivePermissions(Long userId) {
        requireActiveUser(userId);
        return db.query("SELECT DISTINCT p.* FROM demo_permission p JOIN demo_role_permission rp ON rp.permission_id=p.id JOIN demo_role r ON r.id=rp.role_id JOIN demo_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1 AND p.status=1 ORDER BY p.sort,p.id",userId);
    }
    private boolean has(Long userId,String permission) {
        return effectivePermissions(userId).stream().anyMatch(p->permission.equals(p.get("code")));
    }
    private void require(String permission) { if(!has(actor(),permission))throw error(403,"没有所需权限："+permission); }
    private void adminLock() { db.one("SELECT id FROM demo_role WHERE code='ADMIN' FOR UPDATE"); }
    private void keepAdministrator() {
        if(db.count("SELECT COUNT(DISTINCT u.id) FROM demo_user u JOIN demo_user_role ur ON ur.user_id=u.id JOIN demo_role r ON r.id=ur.role_id WHERE u.status=1 AND r.status=1 AND r.code='ADMIN'")==0)
            throw error(409,"至少需要保留一个启用的系统管理员");
    }
    private void verifyNoOwnedActiveTeams(Long userId) {
        if(!db.query("SELECT id FROM demo_team WHERE owner_user_id=? AND status=1 FOR UPDATE",userId).isEmpty())
            throw error(409,"该用户仍负责启用中的团队，请先转交负责人或停用团队");
    }
    public Map<String,Object> login(String login,String password,String ip) {
        String account=login==null?"":login.trim().toLowerCase(Locale.ROOT);
        Map<String,Object> user=db.one("SELECT * FROM demo_user WHERE login=?",account);
        // Use the same expensive hash operation for unknown accounts to avoid an account timing oracle.
        String hash=user==null?DUMMY_HASH:(String)user.get("passwordHash");
        boolean valid=PasswordHasher.matches(password,hash);
        if(!valid || !active(user))throw error(401,"账号或密码错误，或账号不可用");
        db.execute("UPDATE demo_user SET last_login_at=?,last_login_ip=? WHERE id=?",Timestamp.from(Instant.now()),ip,user.get("id"));
        return safe(db.find("demo_user",number(user.get("id")),false));
    }
    private static final String DUMMY_HASH=PasswordHasher.hash("unused-dummy-password");
    public String credentialStamp(Long userId) { return PasswordHasher.stamp((String)requireActiveUser(userId).get("passwordHash")); }
    public Map<String,Object> me() {
        Long id=actor();Map<String,Object> user=safe(requireActiveUser(id));
        user.put("roles",effectiveRoles(id));user.put("permissions",effectivePermissions(id));
        user.put("menus",userMenus(id));return user;
    }
    public Map<String,Object> sessionUser(Long id) { return safe(requireActiveUser(id)); }
    private String snake(String name) { return name.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT); }
    private void object(JsonNode input) { if(input==null || !input.isObject())throw error(400,"请求内容必须为JSON对象"); }
    private Map<String,Object> prepare(String resource,JsonNode input,boolean create) {
        object(input);Map<String,Object> result=new LinkedHashMap<>();Map<String,Integer> defs=FIELDS.get(resource);
        Iterator<Map.Entry<String,JsonNode>> it=input.fields();
        while(it.hasNext()) {
            Map.Entry<String,JsonNode> field=it.next();String key=field.getKey();JsonNode value=field.getValue();
            if("password".equals(key) && "users".equals(resource)) {
                if(value.isNull())throw error(400,"设置密码时不能传入NULL");
                if(!value.isTextual())throw error(400,"password必须为字符串");
                result.put("password_hash",PasswordHasher.hash(value.asText()));continue;
            }
            Integer type=defs.get(key);if(type==null)throw error(400,"不支持的字段："+key);
            Object parsed=null;
            if(!value.isNull()) {
                if(type>0) {
                    if(!value.isTextual())throw error(400,key+"必须为字符串或NULL");
                    String text=value.asText().trim();if(text.length()>type)throw error(400,key+"超过最大长度"+type);
                    parsed=text.isEmpty()?null:text;
                } else if(type==-1 || type==-2) {
                    if(!value.isIntegralNumber() || !value.canConvertToLong())throw error(400,key+"必须为整数或NULL");
                    long n=value.asLong();if(type==-1 && (n<Integer.MIN_VALUE || n>Integer.MAX_VALUE))throw error(400,key+"超出整数范围");
                    if(type==-2 && n<=0)throw error(400,key+"必须为正整数或NULL");parsed=n;
                } else {
                    if(!value.isObject() || value.toString().length()>16384)throw error(400,"preferences必须为不超过16KB的JSON对象或NULL");
                    parsed=value.toString();
                }
            }
            if("login".equals(key) && parsed!=null) {
                parsed=parsed.toString().toLowerCase(Locale.ROOT);
                if(!parsed.toString().matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._@-]{0,63}"))throw error(400,"账号包含不支持的字符");
            }
            if("email".equals(key) && parsed!=null) {
                parsed=parsed.toString().toLowerCase(Locale.ROOT);
                if(!parsed.toString().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw error(400,"邮箱格式不正确");
            }
            if("code".equals(key) && parsed!=null)parsed="roles".equals(resource)?parsed.toString().toUpperCase(Locale.ROOT):parsed.toString().toLowerCase(Locale.ROOT);
            if("status".equals(key) && parsed!=null && !Arrays.asList(0L,1L,"users".equals(resource)?2L:1L).contains(parsed))throw error(400,"不支持的状态值");
            if("type".equals(key) && parsed!=null && !Arrays.asList(1L,2L).contains(parsed))throw error(400,"权限类型只能为1、2或NULL");
            result.put(snake(key),parsed);
        }
        List<String> required="users".equals(resource)?Arrays.asList("login"):
            ("roles".equals(resource)||"permissions".equals(resource))?Arrays.asList("code","name"):Arrays.asList("name");
        for(String field:required)if((create || result.containsKey(field)) && result.get(field)==null)throw error(400,field+"不能为空");
        return result;
    }
    private void audit(Map<String,Object> data,boolean create) {
        Long id=actor();data.put("updated_by",id);if(create)data.put("created_by",id);
    }
    public Map<String,Object> updateProfile(JsonNode input) {
        object(input);Set<String> allowed=new HashSet<>(Arrays.asList("displayName","email","phone","avatarUrl","organization","department","langKey","preferences","remark"));
        input.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw error(400,"个人资料不能修改字段："+k);});
        Long id=actor();Map<String,Object> data=prepare("users",input,false);audit(data,false);db.update("demo_user",id,data);return me();
    }
    public void changePassword(String oldPassword,String newPassword) {
        Long id=actor();Map<String,Object> row=raw("users",id,true);
        if(!PasswordHasher.matches(oldPassword,(String)row.get("passwordHash")))throw error(400,"当前密码不正确");
        db.execute("UPDATE demo_user SET password_hash=?,updated_by=? WHERE id=?",PasswordHasher.hash(newPassword),id,id);
    }
    private void parent(String resource,Long id,Object value) {
        if(value==null)return;Long current=number(value);Set<Long> seen=new HashSet<>();
        while(current!=null) {
            if(current.equals(id) || !seen.add(current))throw error(409,"父级关系不能形成循环");
            current=number(raw(resource,current,true).get("parentId"));
        }
    }
    private void teamAccess(Long id,boolean write) {
        Long user=actor();Map<String,Object> team=raw("teams",id,true);
        if(has(user,"team:manage"))return;
        Map<String,Object> member=db.one("SELECT * FROM demo_team_member WHERE team_id=? AND user_id=?",id,user);
        boolean valid=active(member) && Arrays.asList("member","admin").contains(member.get("memberRole"));
        if(user.equals(number(team.get("ownerUserId"))) && valid)return;
        if(active(team) && valid && (!write || "admin".equals(member.get("memberRole"))))return;
        throw error(403,"没有访问此团队的权限");
    }
    private void teamOwner(Long id) {
        teamAccess(id,true);Map<String,Object> team=raw("teams",id,false);
        if(!actor().equals(number(team.get("ownerUserId"))) && !has(actor(),"team:manage"))throw error(403,"仅负责人可执行此操作");
    }
    public Map<String,Object> create(String resource,JsonNode input) {
        table(resource);
        if("teams".equals(resource))require("team:create");else require(MANAGE.get(resource));
        if("menus".equals(resource)||"permissions".equals(resource))adminLock();
        Map<String,Object> data=prepare(resource,input,true);audit(data,true);
        if(data.containsKey("parent_id"))parent(resource,null,data.get("parent_id"));
        if("teams".equals(resource)) {
            Long owner=data.containsKey("owner_user_id")?number(data.get("owner_user_id")):actor();
            if(owner==null)throw error(400,"团队负责人不能为空");
            if(!owner.equals(actor()) && !has(actor(),"team:manage"))throw error(403,"不能指定其他用户为新团队负责人");
            validTargetUser(owner);data.put("owner_user_id",owner);
        }
        long id=db.insert(table(resource),data);
        if("users".equals(resource)) {
            Map<String,Object> role=db.one("SELECT id FROM demo_role WHERE code='USER'");
            if(role!=null)db.execute("INSERT INTO demo_user_role(user_id,role_id,created_by) VALUES(?,?,?)",id,role.get("id"),actor());
        }
        if("teams".equals(resource))db.execute("INSERT INTO demo_team_member(team_id,user_id,member_role,status,created_by) VALUES(?,?,'member',1,?)",id,data.get("owner_user_id"),actor());
        return safe(raw(resource,id,false));
    }
    public Map<String,Object> update(String resource,Long id,JsonNode input) {
        table(resource);
        if("teams".equals(resource))teamOwner(id);else require(MANAGE.get(resource));
        if(!"teams".equals(resource))adminLock();
        Map<String,Object> old=raw(resource,id,true);Map<String,Object> data=prepare(resource,input,false);
        if("roles".equals(resource) && RESERVED_ROLES.contains(old.get("code"))) {
            if(data.containsKey("code") && !old.get("code").equals(data.get("code")))throw error(409,"内置角色编码不可更改");
            if("ADMIN".equals(old.get("code")) && data.containsKey("status") && !Objects.equals(data.get("status"),1L))throw error(409,"内置管理员角色不能停用");
        }
        if("permissions".equals(resource) && ADMIN_PERMISSIONS.contains(old.get("code"))) {
            if((data.containsKey("code") && !old.get("code").equals(data.get("code"))) || (data.containsKey("status") && !Objects.equals(data.get("status"),1L)))throw error(409,"内置管理权限不能更名或停用");
        }
        if(data.containsKey("parent_id")) {
            // Serialize edits to the same tree, including two concurrent re-parent operations.
            db.query("SELECT id FROM "+table(resource)+" ORDER BY id FOR UPDATE");parent(resource,id,data.get("parent_id"));
        }
        if("users".equals(resource) && data.containsKey("status") && !Objects.equals(data.get("status"),1L))verifyNoOwnedActiveTeams(id);
        if("teams".equals(resource)) {
            if(data.containsKey("owner_user_id"))throw error(400,"请通过负责人转移接口修改团队负责人");
            if(Objects.equals(data.get("status"),1L))validTargetUser(number(old.get("ownerUserId")));
        }
        audit(data,false);db.update(table(resource),id,data);
        if("users".equals(resource))keepAdministrator();
        return safe(raw(resource,id,false));
    }
    public void remove(String resource,Long id) {
        if("users".equals(resource)||"teams".equals(resource)) {
            ObjectNode input=json.createObjectNode();input.put("status",0);update(resource,id,input);return;
        }
        require(MANAGE.get(resource));adminLock();Map<String,Object> old=raw(resource,id,true);
        if("roles".equals(resource)) {
            if(RESERVED_ROLES.contains(old.get("code")))throw error(409,"内置角色不可删除");
            db.execute("DELETE FROM demo_user_role WHERE role_id=?",id);
            db.execute("DELETE FROM demo_role_permission WHERE role_id=?",id);
            db.execute("DELETE FROM demo_role_menu WHERE role_id=?",id);
        } else {
            if(!db.query("SELECT id FROM "+table(resource)+" WHERE parent_id=? FOR UPDATE",id).isEmpty())throw error(409,"请先移动或删除子节点");
            if("permissions".equals(resource) && ADMIN_PERMISSIONS.contains(old.get("code")))throw error(409,"内置管理权限不可删除");
            db.execute("DELETE FROM "+("menus".equals(resource)?"demo_role_menu WHERE menu_id=?":"demo_role_permission WHERE permission_id=?"),id);
        }
        db.execute("DELETE FROM "+table(resource)+" WHERE id=?",id);
    }
    public Map<String,Object> get(String resource,Long id) {
        table(resource);
        if("teams".equals(resource))teamAccess(id,false);
        else if(!("users".equals(resource) && actor().equals(id)))require(MANAGE.get(resource));
        return safe(raw(resource,id,false));
    }
    private int integer(String value,int fallback,int min,int max) {
        if(value==null)return fallback;
        try { int n=Integer.parseInt(value);if(n<min||n>max)throw new NumberFormatException();return n; }
        catch(NumberFormatException e){throw error(400,"分页或状态参数超出允许范围");}
    }
    public Map<String,Object> list(String resource,Map<String,String> query) {
        table(resource);Long user=actor();List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
        if("teams".equals(resource)) {
            if(!has(user,"team:manage")) {
                where+=" AND (owner_user_id=? OR (status=1 AND id IN (SELECT team_id FROM demo_team_member WHERE user_id=? AND status=1 AND member_role IN ('member','admin'))))";
                args.add(user);args.add(user);
            }
        } else require(MANAGE.get(resource));
        String search=query.get("q");if(search==null)search=query.get("login");if(search==null)search=query.get("name");
        if(search!=null && !search.trim().isEmpty()) {
            if(search.length()>200)throw error(400,"搜索内容过长");
            String column="users".equals(resource)?"login":"name";
            where+=" AND "+column+" LIKE ?";args.add("%"+search.trim()+"%");
        }
        if(query.get("status")!=null && !query.get("status").isEmpty()) {where+=" AND status=?";args.add(integer(query.get("status"),0,0,"users".equals(resource)?2:1));}
        int page=integer(query.get("pageIndex"),1,1,1000000),size=integer(query.get("pageSize"),20,1,100);
        long total=db.count("SELECT COUNT(*) FROM "+table(resource)+where,args.toArray());
        args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=db.query("SELECT * FROM "+table(resource)+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::safe).collect(Collectors.toList());
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("total",total);result.put("pageIndex",page);result.put("pageSize",size);return result;
    }
    private List<Map<String,Object>> userMenus(Long id) {
        return asTree(db.query("SELECT DISTINCT m.* FROM demo_menu m JOIN demo_role_menu rm ON rm.menu_id=m.id JOIN demo_role r ON r.id=rm.role_id JOIN demo_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1 AND m.status=1 ORDER BY m.sort,m.id",id));
    }
    public List<Map<String,Object>> relations(String resource,Long id,String relation) {
        raw(resource,id,false);
        if("users".equals(resource)) {
            if(!actor().equals(id))require("user:manage");
            if("roles".equals(relation))return has(actor(),"user:manage")?
                db.query("SELECT r.* FROM demo_role r JOIN demo_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.sort,r.id",id):effectiveRoles(id);
            if("permissions".equals(relation))return active(raw("users",id,false))?effectivePermissions(id):Collections.emptyList();
            if("menus".equals(relation))return active(raw("users",id,false))?userMenus(id):Collections.emptyList();
        } else if("roles".equals(resource)) {
            require("role:manage");
            if("permissions".equals(relation))return db.query("SELECT p.* FROM demo_permission p JOIN demo_role_permission rp ON rp.permission_id=p.id WHERE rp.role_id=? ORDER BY p.sort,p.id",id);
            if("menus".equals(relation))return asTree(db.query("SELECT m.* FROM demo_menu m JOIN demo_role_menu rm ON rm.menu_id=m.id WHERE rm.role_id=? ORDER BY m.sort,m.id",id));
        }
        throw error(404,"未知关联关系");
    }
    public void assign(String resource,Long id,String relation,List<Long> ids) {
        require(MANAGE.get(resource));adminLock();Map<String,Object> owner=raw(resource,id,true);
        if(ids==null || ids.size()>500)throw error(400,"关联ID列表不能为空且最多500项；传空数组表示清空");
        Set<Long> unique=new LinkedHashSet<>(ids);if(unique.contains(null))throw error(400,"关联ID不能为NULL");
        String target,link,left,right;
        if("users".equals(resource)&&"roles".equals(relation)){target="roles";link="demo_user_role";left="user_id";right="role_id";}
        else if("roles".equals(resource)&&"permissions".equals(relation)){target="permissions";link="demo_role_permission";left="role_id";right="permission_id";}
        else if("roles".equals(resource)&&"menus".equals(relation)){target="menus";link="demo_role_menu";left="role_id";right="menu_id";}
        else throw error(404,"未知关联关系");
        List<Map<String,Object>> targets=new ArrayList<>();for(Long item:unique)targets.add(raw(target,item,false));
        if("permissions".equals(relation)&&"ADMIN".equals(owner.get("code"))) {
            Set<Object> codes=targets.stream().map(p->p.get("code")).collect(Collectors.toSet());
            if(!codes.containsAll(ADMIN_PERMISSIONS))throw error(409,"管理员角色必须保留内置管理权限");
        }
        db.execute("DELETE FROM "+link+" WHERE "+left+"=?",id);
        for(Long item:unique)db.execute("INSERT INTO "+link+" ("+left+","+right+",created_by) VALUES(?,?,?)",id,item,actor());
        keepAdministrator();
    }
    public List<Map<String,Object>> tree(String resource) {
        if(!Arrays.asList("menus","permissions").contains(resource))throw error(404,"此资源不支持树形查询");
        require(MANAGE.get(resource));return asTree(db.query("SELECT * FROM "+table(resource)+" ORDER BY sort,id"));
    }
    private List<Map<String,Object>> asTree(List<Map<String,Object>> rows) {
        Map<Long,Map<String,Object>> nodes=new LinkedHashMap<>();List<Map<String,Object>> roots=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Map<String,Object> copy=new LinkedHashMap<>(row);copy.put("children",new ArrayList<Map<String,Object>>());nodes.put(number(copy.get("id")),copy);
        }
        for(Map<String,Object> node:nodes.values()) {
            Long parent=number(node.get("parentId"));
            if(parent==null || !nodes.containsKey(parent))roots.add(node);
            else {
                Set<Long> seen=new HashSet<>();Long current=parent;
                while(current!=null && nodes.containsKey(current)) {if(!seen.add(current))throw error(409,"目录包含循环关系");current=number(nodes.get(current).get("parentId"));}
                @SuppressWarnings("unchecked") List<Map<String,Object>> children=(List<Map<String,Object>>)nodes.get(parent).get("children");children.add(node);
            }
        }
        return roots;
    }
    public List<Map<String,Object>> members(Long teamId) {
        teamAccess(teamId,false);
        return db.query("SELECT tm.*,u.login,u.display_name FROM demo_team_member tm JOIN demo_user u ON u.id=tm.user_id WHERE tm.team_id=? ORDER BY tm.user_id",teamId);
    }
    public Map<String,Object> saveMember(Long teamId,Long userId,JsonNode input) {
        teamAccess(teamId,true);object(input);validTargetUser(userId);
        Map<String,Object> team=raw("teams",teamId,true);
        boolean owner=actor().equals(number(team.get("ownerUserId"))) || has(actor(),"team:manage");
        Map<String,Object> old=db.one("SELECT * FROM demo_team_member WHERE team_id=? AND user_id=? FOR UPDATE",teamId,userId);
        input.fieldNames().forEachRemaining(k->{if(!Arrays.asList("memberRole","status").contains(k))throw error(400,"不支持的成员字段："+k);});
        Object role=old==null?"member":old.get("memberRole");
        Long state=old==null?Long.valueOf(1L):number(old.get("status"));
        if(input.has("memberRole")) {
            JsonNode v=input.get("memberRole");if(!v.isNull() && (!v.isTextual() || !Arrays.asList("member","admin").contains(v.asText())))throw error(400,"成员角色只能为member、admin或NULL");
            role=v.isNull()?null:v.asText();
        }
        if(input.has("status")) {
            JsonNode v=input.get("status");if(!v.isNull() && (!v.isIntegralNumber() || !v.canConvertToInt() || (v.asInt()!=0 && v.asInt()!=1)))throw error(400,"成员状态只能为0、1或NULL");
            state=v.isNull()?null:v.asLong();
        }
        if(!owner && ("admin".equals(role) || (old!=null&&"admin".equals(old.get("memberRole"))) || userId.equals(number(team.get("ownerUserId")))))throw error(403,"团队管理员只能维护普通成员");
        if(userId.equals(number(team.get("ownerUserId"))) && (!Objects.equals(state,1L) || !Arrays.asList("member","admin").contains(role)))throw error(409,"不能停用或移除当前负责人，请先转交团队");
        // Rejoining always drops historical admin privileges unless the owner explicitly grants them again.
        if(old!=null && !active(old) && Objects.equals(state,1L) && !input.has("memberRole"))role="member";
        if(old==null)db.execute("INSERT INTO demo_team_member(team_id,user_id,member_role,status,created_by,updated_by) VALUES(?,?,?,?,?,?)",teamId,userId,role,state,actor(),actor());
        else {
            boolean rejoin=!active(old)&&Objects.equals(state,1L);
            db.execute("UPDATE demo_team_member SET member_role=?,status=?,joined_at="+(rejoin?"CURRENT_TIMESTAMP":"joined_at")+",left_at="+(Objects.equals(state,1L)?"NULL":"CURRENT_TIMESTAMP")+",updated_by=? WHERE team_id=? AND user_id=?",role,state,actor(),teamId,userId);
        }
        return db.one("SELECT * FROM demo_team_member WHERE team_id=? AND user_id=?",teamId,userId);
    }
    public void removeMember(Long teamId,Long userId) {
        // A current member may leave without permission to manage other members.
        if(actor().equals(userId))teamAccess(teamId,false);else teamAccess(teamId,true);
        Map<String,Object> team=raw("teams",teamId,true);
        if(userId.equals(number(team.get("ownerUserId"))))throw error(409,"负责人须先转交团队或停用团队，不能直接退出");
        Map<String,Object> old=db.one("SELECT * FROM demo_team_member WHERE team_id=? AND user_id=?",teamId,userId);
        if(old==null)throw error(404,"成员不存在");
        if(!actor().equals(userId) && "admin".equals(old.get("memberRole")) && !actor().equals(number(team.get("ownerUserId"))) && !has(actor(),"team:manage"))throw error(403,"仅负责人可移除团队管理员");
        db.execute("UPDATE demo_team_member SET status=0,left_at=CURRENT_TIMESTAMP,updated_by=? WHERE team_id=? AND user_id=?",actor(),teamId,userId);
    }
    public void transferTeam(Long teamId,Long userId) {
        teamOwner(teamId);validTargetUser(userId);
        Map<String,Object> member=db.one("SELECT * FROM demo_team_member WHERE team_id=? AND user_id=? FOR UPDATE",teamId,userId);
        if(!active(member) || !Arrays.asList("member","admin").contains(member.get("memberRole")))throw error(409,"新负责人必须是有效团队成员");
        db.execute("UPDATE demo_team SET owner_user_id=?,updated_by=? WHERE id=?",userId,actor(),teamId);
    }
    public void bootstrap(String login,String password) {
        if(db.count("SELECT COUNT(*) FROM demo_user")>0)return;
        if(login==null || login.trim().isEmpty() || password==null || password.isEmpty())throw new IllegalStateException("Empty identity database requires CLASSIFIER_ADMIN_LOGIN and CLASSIFIER_ADMIN_PASSWORD");
        Map<String,Object> user=new LinkedHashMap<>();user.put("login",login.trim().toLowerCase(Locale.ROOT));user.put("display_name","系统管理员");user.put("password_hash",PasswordHasher.hash(password));user.put("status",1);
        long uid=db.insert("demo_user",user);
        Map<String,Long> roleIds=new HashMap<>();
        for(String code:Arrays.asList("ADMIN","USER")) {
            Map<String,Object> r=new LinkedHashMap<>();r.put("code",code);r.put("name","ADMIN".equals(code)?"系统管理员":"普通用户");r.put("status",1);r.put("created_by",uid);roleIds.put(code,db.insert("demo_role",r));
        }
        Set<String> codes=new LinkedHashSet<>(ADMIN_PERMISSIONS);codes.addAll(USER_PERMISSIONS);
        for(String code:codes) {
            Map<String,Object> p=new LinkedHashMap<>();p.put("code",code);p.put("name",permissionName(code));p.put("type",2);p.put("status",1);p.put("created_by",uid);long pid=db.insert("demo_permission",p);
            db.execute("INSERT INTO demo_role_permission(role_id,permission_id,created_by) VALUES(?,?,?)",roleIds.get("ADMIN"),pid,uid);
            if(USER_PERMISSIONS.contains(code))db.execute("INSERT INTO demo_role_permission(role_id,permission_id,created_by) VALUES(?,?,?)",roleIds.get("USER"),pid,uid);
        }
        String[] names={"识别方法","学科知识库","单篇调试","批量识别","设置","用户与团队"};
        String[] paths={"/#/methods","/#/knowledge","/#/debug","/#/batch","/#/settings","/identity/"};
        for(int i=0;i<names.length;i++) {
            Map<String,Object> m=new LinkedHashMap<>();m.put("name",names[i]);m.put("path",paths[i]);m.put("sort",i);m.put("status",1);m.put("created_by",uid);long mid=db.insert("demo_menu",m);
            for(Long rid:roleIds.values())db.execute("INSERT INTO demo_role_menu(role_id,menu_id,created_by) VALUES(?,?,?)",rid,mid,uid);
        }
        db.execute("INSERT INTO demo_user_role(user_id,role_id,created_by) VALUES(?,?,?)",uid,roleIds.get("ADMIN"),uid);
    }
    private String permissionName(String code) {
        Map<String,String> names=new HashMap<>();names.put("user:manage","管理用户");names.put("role:manage","管理系统角色");names.put("permission:manage","管理功能权限");names.put("menu:manage","管理导航菜单");names.put("team:manage","管理所有团队");names.put("team:create","创建团队");
        return names.getOrDefault(code,code);
    }
}
