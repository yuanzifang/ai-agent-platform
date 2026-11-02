package com.platform.system.service;

import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.auth.mapper.UserSessionMapper;
import com.platform.common.mail.MailTemplateService;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.entity.UserInvitation;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.mapper.UserInvitationMapper;
import com.platform.system.mapper.UserQuotaMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：邀请创建与邀请激活建号。 */
class UserAdminServiceInviteTest {

    @Test
    void inviteCreatesPendingTokenForTenant() {
        TestFixture f = fixture();
        JwtPrincipal principal = new JwtPrincipal(9L, 1L, false, List.of("ADMIN"), List.of("user:manage"));
        when(f.invitationMapper.insert(any(UserInvitation.class))).thenAnswer(inv -> {
            inv.getArgument(0, UserInvitation.class).setId(11L);
            return 1;
        });

        Map<String, Object> result = f.service.invite("invitee@example.com", "MEMBER", principal);

        assertEquals(11L, result.get("invitationId"));
        assertTrue(String.valueOf(result.get("token")).length() > 20);
        ArgumentCaptor<UserInvitation> captor = ArgumentCaptor.forClass(UserInvitation.class);
        verify(f.invitationMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(1L, captor.getValue().getTenantId());
        verify(f.mailTemplateService).send(org.mockito.ArgumentMatchers.eq("invitee@example.com"),
                any(), org.mockito.ArgumentMatchers.eq("mail.invite"), any());
    }

    @Test
    void acceptCreatesUserAssignsRoleAndMarksInvitationAccepted() {
        TestFixture f = fixture();
        UserInvitation invitation = new UserInvitation();
        invitation.setId(12L);
        invitation.setTenantId(1L);
        invitation.setEmail("invitee@example.com");
        invitation.setRole("MEMBER");
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(f.invitationMapper.findByToken("token-1")).thenReturn(invitation);
        when(f.userMapper.countByUsernameGlobal("invitee")).thenReturn(0L);
        when(f.userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysUser.class).setId(22L);
            return 1;
        });
        when(f.roleMapper.selectOne(any())).thenReturn(role(5L, "MEMBER"));
        when(f.passwordEncoder.encode("Password1")).thenReturn("encoded");

        Map<String, Object> result = f.service.accept("token-1", "invitee", "Password1");

        assertEquals(22L, result.get("userId"));
        ArgumentCaptor<SysUser> user = ArgumentCaptor.forClass(SysUser.class);
        verify(f.userMapper).insert(user.capture());
        assertEquals(1L, user.getValue().getTenantId());
        assertEquals("ENABLED", user.getValue().getStatus());
        verify(f.userRoleMapper).insert(any(SysUserRole.class));
        ArgumentCaptor<UserInvitation> patch = ArgumentCaptor.forClass(UserInvitation.class);
        verify(f.invitationMapper).updateById(patch.capture());
        assertEquals("ACCEPTED", patch.getValue().getStatus());
        verify(f.auditService).record(22L, "member.accept", "invitation", "12", "SUCCESS",
                Map.of("username", "invitee"));
    }

    private TestFixture fixture() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        UserInvitationMapper invitationMapper = mock(UserInvitationMapper.class);
        MailTemplateService mail = mock(MailTemplateService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        UserAdminService service = new UserAdminService(userMapper, roleMapper, userRoleMapper,
                invitationMapper, mail, encoder, audit, mock(UserQuotaMapper.class),
                mock(AgentRegistryMapper.class), mock(KnowledgeBaseMapper.class), mock(UserSessionMapper.class));
        ReflectionTestUtils.setField(service, "frontendBase", "http://localhost:5173");
        return new TestFixture(service, userMapper, roleMapper, userRoleMapper,
                invitationMapper, mail, encoder, audit);
    }

    private SysRole role(Long id, String key) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleKey(key);
        return role;
    }

    private record TestFixture(UserAdminService service, SysUserMapper userMapper,
                               SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                               UserInvitationMapper invitationMapper, MailTemplateService mailTemplateService,
                               PasswordEncoder passwordEncoder, AuditService auditService) {
    }
}
