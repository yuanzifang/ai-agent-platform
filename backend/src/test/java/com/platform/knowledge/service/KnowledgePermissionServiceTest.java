package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgePermission;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgePermissionMapper;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库可见性/编辑权限与 ACL 同步的回归测试（PRIVATE/TENANT/PARTIAL）。 */
class KnowledgePermissionServiceTest {

    private KnowledgeBaseMapper kbMapper;
    private KnowledgePermissionMapper permMapper;
    private KnowledgeChunkMapper chunkMapper;
    private VectorRepository vectorRepository;
    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private KnowledgePermissionService service;
    private KnowledgeBase kb;

    private final KnowledgePermissionService.Viewer creator =
            new KnowledgePermissionService.Viewer(7L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunk.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgePermission.class);

        kbMapper = mock(KnowledgeBaseMapper.class);
        permMapper = mock(KnowledgePermissionMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        vectorRepository = mock(VectorRepository.class);
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        service = new KnowledgePermissionService(kbMapper, permMapper, chunkMapper,
                vectorRepository, userMapper, userRoleMapper, roleMapper);

        kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);
        kb.setCreatedBy(7L);
        kb.setVisibility("TENANT");
        when(kbMapper.selectById(1L)).thenReturn(kb);
    }

    @Test
    void tenantVisibilityAllowsMembersButNotRestricted() {
        assertTrue(service.canView(kb, new KnowledgePermissionService.Viewer(8L, List.of("MEMBER"))));
        assertFalse(service.canView(kb, new KnowledgePermissionService.Viewer(9L, List.of("RESTRICTED"))));
        // 创建者即使带 RESTRICTED 角色也保留自己的数据可见性
        assertTrue(service.canView(kb, new KnowledgePermissionService.Viewer(7L, List.of("RESTRICTED"))));
    }

    @Test
    void privateVisibilityOnlyCreatorCanViewOrEdit() {
        kb.setVisibility("PRIVATE");
        KnowledgePermissionService.Viewer editor =
                new KnowledgePermissionService.Viewer(8L, List.of("EDITOR"));

        assertFalse(service.canView(kb, editor));
        assertFalse(service.canEdit(kb, editor));
        assertTrue(service.canView(kb, creator));
        assertTrue(service.canEdit(kb, creator));
    }

    @Test
    void partialVisibilityRequiresGrantAndEditGrantForEditing() {
        kb.setVisibility("PARTIAL");
        KnowledgePermissionService.Viewer member =
                new KnowledgePermissionService.Viewer(8L, List.of("MEMBER"));
        when(permMapper.selectOne(any())).thenReturn(grant(8L, "VIEW"));
        assertTrue(service.canView(kb, member));
        assertFalse(service.canEdit(kb, member));

        when(permMapper.selectOne(any())).thenReturn(grant(8L, "EDIT"));
        assertTrue(service.canEdit(kb, member));
        // 管理者被授予 VIEW 后也可以编辑（需求 3.9(2)）
        assertTrue(service.canEdit(kb, new KnowledgePermissionService.Viewer(8L, List.of("ADMIN"))));
    }

    @Test
    void tenantEditorsCanEditWhileMembersAreReadOnly() {
        assertTrue(service.canEdit(kb, new KnowledgePermissionService.Viewer(8L, List.of("EDITOR"))));
        assertTrue(service.canEdit(kb, new KnowledgePermissionService.Viewer(8L, List.of("ADMIN"))));
        assertFalse(service.canEdit(kb, new KnowledgePermissionService.Viewer(8L, List.of("MEMBER"))));
        assertFalse(service.canEdit(kb, new KnowledgePermissionService.Viewer(8L, List.of("RESTRICTED"))));
    }

    @Test
    void visibilityNormalizationKeepsCanViewAndAclConsistent() {
        kb.setVisibility(" partial ");
        assertEquals("PARTIAL", KnowledgePermissionService.visibilityOf(kb));
        kb.setVisibility("unknown");
        assertEquals("TENANT", KnowledgePermissionService.visibilityOf(kb));
        kb.setVisibility(null);
        assertEquals("TENANT", KnowledgePermissionService.visibilityOf(kb));
    }

    @Test
    void aclOfIncludesCreatorAndPartialMembers() {
        kb.setVisibility("PARTIAL");
        when(permMapper.selectList(any())).thenReturn(List.of(grant(8L, "EDIT")));

        KnowledgePermissionService.Acl acl = service.aclOf(kb);
        assertEquals(List.of(7L, 8L), acl.allowedUsers());
        assertFalse(acl.tenantWide());

        kb.setVisibility("TENANT");
        KnowledgePermissionService.Acl tenantAcl = service.aclOf(kb);
        assertEquals(List.of(7L), tenantAcl.allowedUsers());
        assertTrue(tenantAcl.tenantWide());
    }

    @Test
    void validateAclUsersRejectsCrossTenantAndRestrictedMembers() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, 9L));
        assertDoesNotThrow(() -> service.validateAclUsers(9L, List.of(8L)));

        when(userMapper.selectById(8L)).thenReturn(user(8L, 10L));
        BusinessException crossTenant = assertThrows(BusinessException.class,
                () -> service.validateAclUsers(9L, List.of(8L)));
        assertEquals(400, crossTenant.getCode());

        when(userMapper.selectById(8L)).thenReturn(user(8L, 9L));
        SysRole restricted = new SysRole();
        restricted.setId(3L);
        restricted.setRoleKey("RESTRICTED");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(8L, 3L)));
        when(roleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(restricted));
        BusinessException restrictedError = assertThrows(BusinessException.class,
                () -> service.validateAclUsers(9L, List.of(8L)));
        assertEquals(400, restrictedError.getCode());
        assertTrue(restrictedError.getMessage().contains("受限"));
    }

    @Test
    void replacePartialWritesMembersAndSynchronizesAcl() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, 9L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(permMapper.selectList(any())).thenReturn(List.of(grant(8L, "EDIT")));

        service.replace(1L, "PARTIAL",
                List.of(new KnowledgePermissionService.Member(8L, "EDIT")), creator);

        ArgumentCaptor<KnowledgePermission> inserted =
                ArgumentCaptor.forClass(KnowledgePermission.class);
        verify(permMapper).insert(inserted.capture());
        assertEquals(1L, inserted.getValue().getKnowledgeBaseId());
        assertEquals(8L, inserted.getValue().getSubjectId());
        assertEquals("EDIT", inserted.getValue().getPermission());
        assertEquals("USER", inserted.getValue().getSubjectType());

        verify(vectorRepository).updateAclForKnowledgeBase(9L, 1L, List.of(7L, 8L), false);
        verify(chunkMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void replaceRejectsCrossTenantMemberBeforeAclSync() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, 10L));

        BusinessException error = assertThrows(BusinessException.class, () -> service.replace(
                1L, "PARTIAL", List.of(new KnowledgePermissionService.Member(8L, "VIEW")), creator));
        assertEquals(400, error.getCode());
        verify(permMapper, never()).insert(any(KnowledgePermission.class));
        verify(vectorRepository, never()).updateAclForKnowledgeBase(
                any(), any(), any(), anyBoolean());
    }

    @Test
    void findVisibleReturnsNullForInvisibleOrDeletedKb() {
        kb.setVisibility("PRIVATE");
        assertTrue(service.findVisible(1L, new KnowledgePermissionService.Viewer(8L, List.of("MEMBER"))) == null);
        when(kbMapper.selectById(2L)).thenReturn(null);
        assertTrue(service.findVisible(2L, creator) == null);
    }

    private KnowledgePermission grant(Long userId, String permission) {
        KnowledgePermission row = new KnowledgePermission();
        row.setId(userId * 10);
        row.setKnowledgeBaseId(1L);
        row.setSubjectType("USER");
        row.setSubjectId(userId);
        row.setPermission(permission);
        row.setTenantId(9L);
        return row;
    }

    private SysUser user(Long id, Long tenantId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("u" + id);
        return user;
    }

    private SysUserRole userRole(Long userId, Long roleId) {
        SysUserRole row = new SysUserRole();
        row.setUserId(userId);
        row.setRoleId(roleId);
        return row;
    }
}
