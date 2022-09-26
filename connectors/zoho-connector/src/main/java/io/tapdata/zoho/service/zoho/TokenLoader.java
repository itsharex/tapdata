package io.tapdata.zoho.service.zoho;

import io.tapdata.entity.error.CoreException;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.entity.utils.cache.KVMap;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.zoho.entity.*;
import io.tapdata.zoho.enums.HttpCode;
import io.tapdata.zoho.utils.Checker;
import io.tapdata.zoho.utils.ZoHoHttp;

public class TokenLoader extends ZoHoStarter implements ZoHoBase {
    private static final String TAG = TokenLoader.class.getSimpleName();
    protected TokenLoader(TapConnectionContext tapConnectionContext) {
        super(tapConnectionContext);
    }
    public static TokenLoader create(TapConnectionContext tapConnectionContext){
        return new TokenLoader(tapConnectionContext);
    }
    /**
     *  刷新accessToken
     * @user Gavin
     * */
    public RefreshTokenEntity refreshToken(){
        ContextConfig contextConfig = super.veryContextConfigAndNodeConfig();
        return refreshToken(this.refreshTokenFromConfig(),contextConfig.getClientId(),contextConfig.getClientSecret());
    }
    public RefreshTokenEntity refreshToken(String refreshToken,String clientId,String clientSecret){
        HttpResult post = refresh(refreshToken, clientId, clientSecret);
        String code = post.getCode();
        if (HttpCode.SUCCEED.getCode().equals(code)){
            return RefreshTokenEntity.create(post.getResult()).message(HttpCode.SUCCEED.getMessage());
        }else {
            TapLogger.error(TAG,"{} | {}",code,post.getResult().get(HttpCode.ERROR.getCode()));
            throw new CoreException(code+"|"+post.getResult().get(HttpCode.ERROR.getCode()));
        }
    }
    public HttpResult refresh(){
        ContextConfig contextConfig = super.veryContextConfigAndNodeConfig();
        return this.refresh(this.refreshTokenFromConfig(),contextConfig.getClientId(),contextConfig.getClientSecret());
    }

    public HttpResult refresh(String refreshToken,String clientId,String clientSecret){
        HttpEntity<String,Object> form = HttpEntity.create()
                .build("refresh_token",refreshToken)
                .build("client_id",clientId)
                .build("client_secret",clientSecret)
                .build("scope",ZO_HO_BASE_SCOPE)
                .build("redirect_uri","https://www.zylker.com/oauthgrant")
                .build("grant_type","refresh_token");
        ZoHoHttp http = ZoHoHttp.create(String.format(ZO_HO_BASE_TOKEN_URL,"/oauth/v2/token"), HttpType.POST).form(form);
        TapLogger.debug(TAG,"Try to refresh AccessToken.");
        HttpResult post = http.post();
        //刷新AccessToken后把心token更新到stateMap中，方便同一任务下次HTTP请求使用
        if (HttpCode.SUCCEED.getCode().equals(post.getCode()) && Checker.isNotEmpty(post.getResult())){
            Object accessTokenObj = post.getResult().get("access_token");
            if (Checker.isNotEmpty(accessTokenObj)) {
                this.addNewAccessTokenToStateMap((String)accessTokenObj);
            }
        }
        return post;
    }
    public TokenEntity getToken(){
        ContextConfig contextConfig = super.veryContextConfigAndNodeConfig();
        return this.getToken(contextConfig.getClientId(),contextConfig.getClientSecret(),contextConfig.getGenerateCode());
    }
    public TokenEntity getToken(String clientId,String clientSecret,String generateCode){
        HttpEntity<String,Object> form = HttpEntity.create()
                .build("code",generateCode)
                .build("client_id",clientId)
                .build("client_secret",clientSecret)
                .build("redirect_uri","https://www.zylker.com/oauthgrant")
                .build("grant_type","authorization_code");
        ZoHoHttp http = ZoHoHttp.create(String.format(ZO_HO_BASE_TOKEN_URL,"/oauth/v2/token"), HttpType.POST).form(form);
        TapLogger.debug(TAG,"Try to get AccessToken and RefreshToken.");
        return TokenEntity.create().entity(http.post()) ;
    }
}
