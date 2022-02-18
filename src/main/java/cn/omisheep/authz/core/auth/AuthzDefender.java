package cn.omisheep.authz.core.auth;

import cn.omisheep.authz.PermLibrary;
import cn.omisheep.authz.core.RequestExceptionStatus;
import cn.omisheep.authz.core.auth.deviced.DefaultDevice;
import cn.omisheep.authz.core.auth.deviced.UserDevicesDict;
import cn.omisheep.authz.core.auth.ipf.HttpMeta;
import cn.omisheep.authz.core.auth.rpd.PermissionDict;
import cn.omisheep.authz.core.tk.Token;
import cn.omisheep.authz.core.tk.TokenHelper;
import cn.omisheep.authz.core.tk.TokenPair;
import cn.omisheep.authz.core.util.LogUtils;
import cn.omisheep.commons.util.CollectionUtils;
import cn.omisheep.commons.util.HttpUtils;
import cn.omisheep.commons.util.TimeUtils;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletResponse;
import java.util.*;

/**
 * qq: 1269670415
 *
 * @author zhou xin chen
 */
public class AuthzDefender {

    private final UserDevicesDict userDevicesDict;
    private final PermissionDict permissionDict;
    private final PermFact permFact;

    public AuthzDefender(UserDevicesDict userDevicesDict, PermissionDict permissionDict, PermFact permFact) {
        this.userDevicesDict = userDevicesDict;
        this.permissionDict = permissionDict;
        this.permFact = permFact;
    }

    /**
     * @param userId     用户id
     * @param deviceType 设备系统类型
     * @param deviceId   设备id
     * @return 授权后的tokenPair(accessToken以及refreshToken)
     */
    public TokenPair grant(Object userId, String deviceType, String deviceId) {
        TokenPair tokenPair = TokenHelper.createTokenPair(userId, deviceType, deviceId);
        HttpServletResponse response = ((ServletRequestAttributes) (RequestContextHolder.currentRequestAttributes())).getResponse();
        HttpMeta httpMeta = (HttpMeta) ((ServletRequestAttributes) (RequestContextHolder.currentRequestAttributes())).getRequest().getAttribute("AU_HTTP_META");
        if (response != null) {
            response.addCookie(TokenHelper.generateCookie(tokenPair.getAccessToken()));
        }

        DefaultDevice device = new DefaultDevice();
        device.setType(deviceType).setId(deviceId).setLastRequestTime(TimeUtils.now()).setIp(httpMeta.getIp());
        userDevicesDict.addUser(userId, tokenPair, device);
        return tokenPair;
    }

    /**
     * access过期刷新接口
     * 如果使用单token，则直接使用accessToken即可，在accessToken过期时再重新登录。
     * 使用双token时，accessToken过期时，可以利用refreshToken在此接口中刷新获得一个新的accessToken。
     *
     * @param refreshToken 与accessToken一起授予的refreshToken
     * @return 刷新成功（Token）/ 失败（null）
     */
    public TokenPair refreshToken(String refreshToken) {
        try {
            TokenPair tokenPair = TokenHelper.refreshToken(refreshToken);
            if (userDevicesDict.refreshUser(tokenPair)) {
                HttpServletResponse response = ((ServletRequestAttributes) (RequestContextHolder.currentRequestAttributes())).getResponse();
                if (response != null) {
                    response.addCookie(TokenHelper.generateCookie(tokenPair.getAccessToken()));
                }
                return tokenPair;
            }
            return null;
        } catch (ExpiredJwtException e) {
            return null;
        }
    }

    public boolean requireProtect(String method, String api) {
        Map<String, PermRolesMeta> map = permissionDict.getAuMap().get(method);
        if (map == null) return false;
        return map.get(api) != null;
    }

    /**
     * 合法性判断
     *
     * @param httpMeta httpMeta
     * @return 合法性判断
     */
    @SneakyThrows
    @SuppressWarnings("all")
    public boolean verify(HttpMeta httpMeta) {
        PermRolesMeta permRolesMeta = permissionDict.getAuMap().get(httpMeta.getMethod()).get(httpMeta.getApi());

        if (!httpMeta.isHasTokenCookie()) {
            logs("Require Login", httpMeta, permRolesMeta);
            HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.REQUIRE_LOGIN);
            return false;
        }

        if (httpMeta.getTokenException() != null) {
            switch (httpMeta.getTokenException()) {
                case ExpiredJwtException:
                    logs("Forbid : expired token exception", httpMeta, permRolesMeta);
                    HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.ACCESS_TOKEN_OVERDUE);
                    return false;
                case MalformedJwtException:
                    logs("Forbid : malformed token exception", httpMeta, permRolesMeta);
                    HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.TOKEN_EXCEPTION);
                    return false;
                case SignatureException:
                    logs("Forbid : signature exception", httpMeta, permRolesMeta);
                    HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.TOKEN_EXCEPTION);
                    return false;
            }
        }

        Token accessToken = httpMeta.getToken();

        switch (userDevicesDict.userStatus(accessToken.getUserId(), accessToken.getDeviceType(), accessToken.getDeviceId(), accessToken.getTokenId())) {
            case 1:
                // accessToken过期
                logs("Forbid : expired token exception", httpMeta, permRolesMeta);
                HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.ACCESS_TOKEN_OVERDUE);
                return false;
            case 2:
                // 需要重新登录
                logs("Require Login", httpMeta, permRolesMeta);
                HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.REQUIRE_LOGIN);
                return false;
            case 3:
                // 在别处登录
                logs("forbid : may have logged in elsewhere", httpMeta, permRolesMeta);
                HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.LOGIN_EXCEPTION);
                return false;
        }

        List<String> roles = null;
        boolean e1 = CollectionUtils.isEmpty(permRolesMeta.getRequireRoles());
        boolean e2 = CollectionUtils.isEmpty(permRolesMeta.getExcludeRoles());
        if (!e1 || !e2) {
            PermLibrary permLibrary = permFact.getPermLibrary();
            //
            roles = permLibrary.getRolesByUserId(accessToken.getUserId());
            if (!e1 && !CollectionUtils.containsSub(permRolesMeta.getRequireRoles(), roles) || !e2 && CollectionUtils.containsSub(permRolesMeta.getExcludeRoles(), roles)) {
                logs("Forbid : permissions exception", httpMeta, permRolesMeta);
                HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.PERM_EXCEPTION);
                return false;
            }
        }

        boolean e3 = CollectionUtils.isEmpty(permRolesMeta.getRequirePermissions());
        boolean e4 = CollectionUtils.isEmpty(permRolesMeta.getExcludePermissions());
        if (!e3 || !e4) {
            PermLibrary permLibrary = permFact.getPermLibrary();
            List permissions = permLibrary.getPermissionsByUserId(accessToken.getUserId());
            if (permissions != null) {
                if (!e3 && !CollectionUtils.containsSub(permRolesMeta.getRequirePermissions(), permissions) || !e4 && CollectionUtils.containsSub(permRolesMeta.getExcludePermissions(), permissions)) {
                    logs("Forbid : permissions exception", httpMeta, permRolesMeta);
                    HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.PERM_EXCEPTION);
                    return false;
                }
            } else {
                if (e1 && e2) {
                    roles = permLibrary.getRolesByUserId(accessToken.getUserId());
                }
                HashSet<String> perms = new HashSet<>(); // 用户所拥有的权限
                for (String role : Optional.of(roles).orElse(new ArrayList<>())) {
                    List permissionsByRole = permLibrary.getPermissionsByRole(role);
                    if (permissionsByRole != null) {
                        perms.addAll(permissionsByRole);
                    }
                    if (!e4 && CollectionUtils.containsSub(permRolesMeta.getExcludePermissions(), permissionsByRole)) {
                        logs("Forbid : permissions exception", httpMeta, permRolesMeta);
                        HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.PERM_EXCEPTION);
                        return false;
                    }
                }
                if (!e3 && !CollectionUtils.containsSub(permRolesMeta.getRequirePermissions(), perms)) {
                    logs("Forbid : permissions exception", httpMeta, permRolesMeta);
                    HttpUtils.returnResponse(HttpStatus.FORBIDDEN, RequestExceptionStatus.PERM_EXCEPTION);
                    return false;
                }
            }

        }

        logs("Success", httpMeta, permRolesMeta);
        return true;
    }

    private void logs(String status, HttpMeta httpMeta, PermRolesMeta meta) {
        Token token = httpMeta.getToken();
        if (token == null) {
            LogUtils.pushLogToRequest("「{}」\t{}",
                    status, meta);
        } else {
            LogUtils.pushLogToRequest("「{}」\t\t{}\t, userId: [{}]\t, deviceType&deviceId [ {} , {} ]",
                    status, meta, token.getUserId(), token.getDeviceType(), token.getDeviceId());
        }
    }


}
