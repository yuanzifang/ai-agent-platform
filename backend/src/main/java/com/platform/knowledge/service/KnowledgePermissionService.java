package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgePermission;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgePermissionMapper;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库可见性/编辑权限判定与授权管理（需求 3.6.3(5)、3.9(2)）：
 * <ul>
 *   <li>PRIVATE：仅创建者可见可编辑；</li>
 *   <li>TENANT：租户内非受限成员可见；管理员/编辑者（或创建者）可编辑；</li>
 *   <li>PARTIAL：仅被授权成员可见，EDIT 授权或管理员/编辑者被授权后可编辑；</li>
 *   <li>管理权与可见性分离：管理员不因角色自动获得知识库内容可见性。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class KnowledgePermissionService {

    /** 调用方身份：userId + 角色 key（受限成员判定依赖 RESTRICTED）。 */
    public record Viewer(Long userId, List<String> roles) {
        public static Viewer of(Long userId, List<String> roles) {
            return new Viewer(userId, roles);
        }
    }

    public record Member(Long userId, String permission) {
    }

    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "TENANT", "PARTIAL");
    private static final Set<String> PERMISSIONS = Set.of("VIEW", "EDIT");

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgePermissionMapper permMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;

    // ---------- 判定 ----------

    public boolean canView(KnowledgeBase kb, Viewer viewer) {
        if (kb == null || viewer == null || viewer.userId() == null) return false;
        if (isCreator(kb, viewer)) return true;
        String visibility = visibilityOf(kb);
        return switch (visibility) {
            case "PRIVATE" -> false;
            case "PARTIAL" -> findGrant(kb.getId(), viewer.userId()) != null;
            default -> !isRestricted(viewer); // TENANT：所有团队成员，不含受限成员
        };
    }

    public boolean canEdit(KnowledgeBase kb, Viewer viewer) {
        if (kb == null || viewer == null || viewer.userId() == null) return false;
        if (isCreator(kb, viewer)) return true;
        String visibility = visibilityOf(kb);
        if ("PRIVATE".equals(visibility)) return false;
        KnowledgePermission grant = findGrant(kb.getId(), viewer.userId());
        if ("PARTIAL".equals(visibility)) {
            // 被授权即获得编辑（EDIT），管理员/编辑者被授予可见权限后亦可编辑（需求 3.9(2)）
            return grant != null && ("EDIT".equals(grant.getPermission()) || isEditorRole(viewer));
        }
        // TENANT：管理员/编辑者可见即可编辑；普通成员只读
        return isEditorRole(viewer) && !isRestricted(viewer);
    }

    /** 读取校验：不存在 404，无可见权限 403。 */
    public KnowledgeBase requireView(Long kbId, Viewer viewer) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) throw new BusinessException(404, "知识库不存在");
        if (!canView(kb, viewer)) throw new BusinessException(403, "无权访问该知识库");
        return kb;
    }

    /** 编辑校验：上传/回调/分段管理/权限设置均需编辑权限。 */
    public KnowledgeBase requireEdit(Long kbId, Viewer viewer) {
        KnowledgeBase kb = requireView(kbId, viewer);
        if (!canEdit(kb, viewer)) throw new BusinessException(403, "无权编辑该知识库");
        return kb;
    }

    /** RAG 运行时：不可见返回 null（需求 3.6.3(5)：检索时结合用户身份过滤）。 */
    public KnowledgeBase findVisible(Long kbId, Viewer viewer) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        return kb != null && canView(kb, viewer) ? kb : null;
    }

    // ---------- 授权管理 ----------

    /** 权限面板数据（需编辑权限）。 */
    public Map<String, Object> permissionOf(Long kbId, Viewer viewer) {
        KnowledgeBase kb = requireEdit(kbId, viewer);
        List<KnowledgePermission> rows = permMapper.selectList(new LambdaQueryWrapper<KnowledgePermission>()
                .eq(KnowledgePermission::getKnowledgeBaseId, kbId)
                .eq(KnowledgePermission::getSubjectType, "USER")
                .orderByAsc(KnowledgePermission::getId));
        Map<Long, SysUser> users = rows.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(rows.stream().map(KnowledgePermission::getSubjectId).toList())
                        .stream().collect(Collectors.toMap(SysUser::getId, u -> u));
        List<Map<String, Object>> members = new ArrayList<>();
        for (KnowledgePermission row : rows) {
            SysUser u = users.get(row.getSubjectId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", row.getSubjectId());
            m.put("username", u == null ? null : u.getUsername());
            m.put("displayName", u == null || u.getDisplayName() == null ? null : u.getDisplayName());
            m.put("permission", row.getPermission());
            members.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visibility", visibilityOf(kb));
        result.put("creatorId", kb.getCreatedBy());
        result.put("canEdit", true);
        result.put("members", members);
        return result;
    }

    /** 全量覆盖（PUT 语义）：visible + 成员授权；PRIVATE/TENANT 清空授权行。 */
    @Transactional
    public void replace(Long kbId, String visibility, List<Member> members, Viewer viewer) {
        KnowledgeBase kb = requireEdit(kbId, viewer);
        String vis = visibility == null ? "" : visibility.trim().toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(vis)) {
            throw new BusinessException(400, "可见范围仅支持 PRIVATE/TENANT/PARTIAL");
        }

        kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getVisibility, vis));
        permMapper.delete(new LambdaQueryWrapper<KnowledgePermission>()
                .eq(KnowledgePermission::getKnowledgeBaseId, kbId));
        if (!"PARTIAL".equals(vis) || members == null) return;

        Set<Long> seen = new HashSet<>();
        for (Member member : members) {
            if (member == null || member.userId() == null) continue;
            if (member.userId().equals(kb.getCreatedBy())) continue; // 创建者默认具备全部权限
            if (!seen.add(member.userId())) continue;
            SysUser user = userMapper.selectById(member.userId());
            if (user == null || !Objects.equals(user.getTenantId(), kb.getTenantId())) {
                throw new BusinessException(400, "授权成员不存在或不属于当前租户");
            }
            if (isRestrictedUser(user.getId())) {
                throw new BusinessException(400, "受限成员无知识库权限，不可授权");
            }
            String perm = member.permission() == null ? "VIEW"
                    : member.permission().trim().toUpperCase(Locale.ROOT);
            if (!PERMISSIONS.contains(perm)) {
                throw new BusinessException(400, "成员权限仅支持 VIEW/EDIT");
            }
            KnowledgePermission row = new KnowledgePermission();
            row.setKnowledgeBaseId(kbId);
            row.setSubjectType("USER");
            row.setSubjectId(user.getId());
            row.setPermission(perm);
            row.setTenantId(kb.getTenantId());
            row.setCreatedAt(LocalDateTime.now());
            permMapper.insert(row);
        }
    }

    // ---------- 内部 ----------

    public static String visibilityOf(KnowledgeBase kb) {
        String v = kb.getVisibility();
        return v == null || v.isBlank() ? "TENANT" : v;
    }

    private KnowledgePermission findGrant(Long kbId, Long userId) {
        return permMapper.selectOne(new LambdaQueryWrapper<KnowledgePermission>()
                .eq(KnowledgePermission::getKnowledgeBaseId, kbId)
                .eq(KnowledgePermission::getSubjectType, "USER")
                .eq(KnowledgePermission::getSubjectId, userId)
                .last("LIMIT 1"));
    }

    private boolean isRestrictedUser(Long userId) {
        List<SysUserRole> urs = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        if (urs.isEmpty()) return false;
        List<SysRole> roles = roleMapper.selectBatchIds(
                urs.stream().map(SysUserRole::getRoleId).toList());
        return roles.stream().anyMatch(r -> "RESTRICTED".equals(r.getRoleKey()));
    }

    private static boolean isCreator(KnowledgeBase kb, Viewer viewer) {
        return Objects.equals(kb.getCreatedBy(), viewer.userId());
    }

    private static boolean hasRole(Viewer viewer, String role) {
        return viewer.roles() != null && viewer.roles().contains(role);
    }

    private static boolean isEditorRole(Viewer viewer) {
        return hasRole(viewer, "OWNER") || hasRole(viewer, "ADMIN") || hasRole(viewer, "EDITOR");
    }

    private static boolean isRestricted(Viewer viewer) {
        return hasRole(viewer, "RESTRICTED") && !isEditorRole(viewer);
    }
}
