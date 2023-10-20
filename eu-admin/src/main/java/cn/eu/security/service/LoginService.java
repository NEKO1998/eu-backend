package cn.eu.security.service;

import cn.dev33.satoken.spring.SpringMVCUtil;
import cn.dev33.satoken.stp.SaLoginConfig;
import cn.dev33.satoken.stp.StpUtil;
import cn.eu.common.constants.Constants;
import cn.eu.common.enums.LoginType;
import cn.eu.common.enums.SysUserStatus;
import cn.eu.common.utils.*;
import cn.eu.properties.EuProperties;
import cn.eu.security.PasswordEncoder;
import cn.eu.security.SecurityUtil;
import cn.eu.security.model.AuthUser;
import cn.eu.system.domain.SysRole;
import cn.eu.system.domain.SysUser;
import cn.eu.system.service.ISysDeptService;
import cn.eu.system.service.ISysRoleService;
import cn.eu.system.service.ISysUserService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import eu.bitwalker.useragentutils.UserAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author zhaoeryu
 * @since 2023/6/5
 */
@Slf4j
@Service
public class LoginService {

    @Autowired
    ISysUserService sysUserService;
    @Autowired
    RedisUtil redisUtil;
    @Autowired
    EuProperties euProperties;
    @Autowired
    ISysDeptService sysDeptService;
    @Autowired
    ISysRoleService sysRoleService;
    
    public String login(String username, String password, String uuid, String code) {
        // 校验验证码
        checkCaptcha(uuid, code);

        // 校验账号是否被锁定
        final String lockLoginRedisKey = Constants.LOCK_LOGIN_REDIS_KEY + username;
        final String lockErrorMessageTpl = "账号已被锁定，请于%s后再试";
        checkLock(lockLoginRedisKey, lockErrorMessageTpl);

        // 获取账号信息
        SysUser user = getAccount(username);

        // 校验密码是否正确以及错误次数是否超限
        checkPassword(username, password, user.getPassword(), lockLoginRedisKey, lockErrorMessageTpl);

        // 校验账号状态
        checkAccountStatus(user.getStatus());

        // 组装认证后的用户实体 - 用于保存到缓存中
        AuthUser authUser = assembleAuthUser(user);

        // 进行登录
        StpUtil.login(user.getId(), SaLoginConfig
                .setDevice(LoginType.ADMIN.getValue())
//                .setExtra(Constants.USER_KEY, authUser)
//                .setExtra(Constants.IS_ADMIN_KEY, user.getAdmin())
        );
        SecurityUtil.setLoginUser(authUser);
        SecurityUtil.setLoginUserIsAdmin(user.getAdmin());
        // 记录登录用户的角色
        SecurityUtil.setLoginUserRoles(sysRoleService.getRolesByUserId(user.getId()));

        // 记录登录信息
        recordLoginInfo(authUser);

        // 返回token
        return StpUtil.getTokenValue();
    }

    private void checkCaptcha(String uuid, String code) {
        final String captchaRedisKey = Constants.CAPTCHA_REDIS_KEY + uuid;
        Assert.isTrue(redisUtil.hasKey(captchaRedisKey), "验证码已过期");
        String captcha = redisUtil.get(captchaRedisKey);
        // 验证码使用后删除
        redisUtil.delete(captchaRedisKey);
        Assert.isTrue(code.equals(captcha), "验证码错误");
    }
    
    private void checkLock(String lockLoginRedisKey, String lockErrorMessageTpl) {
        Long lockExpireSeconds = redisUtil.getExpire(lockLoginRedisKey, TimeUnit.SECONDS);
        Assert.isFalse(redisUtil.hasKey(lockLoginRedisKey), String.format(lockErrorMessageTpl, DateUtil.secondsToTime(lockExpireSeconds.intValue())));
    }
    
    private SysUser getAccount(String username) {
        SysUser user = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 1")
        );
        Assert.notNull(user, "用户名或密码错误");
        return user;
    }

    /**
     * 校验密码
     * @param username 用户填写的用户名
     * @param password 用户填写的密码
     * @param correctPassword 数据库中的密码
     * @param lockLoginRedisKey 账号锁定的redis key
     * @param lockErrorMessageTpl 账号锁定的错误提示模板
     */
    private void checkPassword(String username, String password, String correctPassword, String lockLoginRedisKey, String lockErrorMessageTpl) {
        // 解密密码
        password = RsaUtil.decryptByPrivateKey(euProperties.getRsa().getPrivateKey(), password);
        
        // 和数据库中的密码进行比对
        boolean isCorrectPassword = PasswordEncoder.matches(password, correctPassword);

        final String tryLoginRedisKey = Constants.TRY_LOGIN_COUNT_REDIS_KEY + username;
        if (!isCorrectPassword) {
            String countStr = redisUtil.get(tryLoginRedisKey);
            if (StrUtil.isBlank(countStr)) {
                countStr = "0";
            }
            int count = Integer.parseInt(countStr);
            if (count >= Constants.MAX_TRY_LOGIN_LIMIT) {
                redisUtil.setEx(lockLoginRedisKey, "1", Constants.LOCK_TIME, TimeUnit.SECONDS);
                throw new RuntimeException(String.format(lockErrorMessageTpl, DateUtil.secondsToTime(Constants.LOCK_TIME)));
            }
            count++;
            redisUtil.setEx(tryLoginRedisKey, String.valueOf(count), Constants.TRY_LOGIN_CACHE_TIME, TimeUnit.SECONDS);
            throw new RuntimeException("用户名或密码错误");
        }
        // 登录成功，删除尝试登录次数缓存
        redisUtil.delete(tryLoginRedisKey);
    }

    private void checkAccountStatus(Integer status) {
        SysUserStatus sysUserStatus = SysUserStatus.valueOf(status);
        if (sysUserStatus != null && SysUserStatus.NORMAL != sysUserStatus) {
            switch (sysUserStatus) {
                case DISABLE:
                    throw new RuntimeException("账号已被禁用");
                case DELETED:
                    throw new RuntimeException("账号已被删除");
                default:
                    // nothing    
            }
        }
    }

    private AuthUser assembleAuthUser(SysUser user) {
        String clientIp = IpUtil.getClientIp();
        String ipRegion = IpUtil.getIpRegion(clientIp);
        String prevIpRegion = StrUtil.isBlank(user.getLoginIp()) ? null : IpUtil.getIpRegion(user.getLoginIp());

        // 部门
        String deptName = null;
        List<String> deptNames = null;
        if (user.getDeptId() != null) {
            deptNames = sysDeptService.getParentDeptNames(user.getDeptId());
            if (CollUtil.isNotEmpty(deptNames)) {
                deptName = deptNames.get(deptNames.size() - 1);
            }
        }

        final UserAgent userAgent = UserAgent.parseUserAgentString(SpringMVCUtil.getRequest().getHeader("User-Agent"));
        String os = userAgent.getOperatingSystem().getName();
        String browser = userAgent.getBrowser().getName();

        AuthUser authUser = new AuthUser();
        authUser.setUserId(user.getId());
        authUser.setUsername(user.getUsername());
        authUser.setNickname(user.getNickname());
        authUser.setAvatar(user.getAvatar());
        authUser.setMobile(user.getMobile());
        authUser.setEmail(user.getEmail());
        authUser.setSex(user.getSex());
        authUser.setAdmin(user.getAdmin());
        authUser.setPrevLoginIp(user.getLoginIp());
        authUser.setPrevLoginRegion(prevIpRegion);
        authUser.setPrevLoginTime(user.getLoginTime());
        authUser.setLoginIp(clientIp);
        authUser.setLoginRegion(ipRegion);
        authUser.setLoginTime(LocalDateTime.now());
        authUser.setCreateTime(user.getCreateTime());
        authUser.setDeptId(user.getDeptId());
        authUser.setDeptName(deptName);
        authUser.setDeptNames(deptNames);
        authUser.setBrowser(browser);
        authUser.setOs(os);
        return authUser;
    }

    private void recordLoginInfo(AuthUser authUser) {
        LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, authUser.getUserId())
                .set(SysUser::getLoginIp, authUser.getLoginIp())
                .set(SysUser::getLoginTime, LocalDateTime.now())
                .set(SysUser::getLastActiveTime, LocalDateTime.now());
        sysUserService.update(updateWrapper);
    }

}
