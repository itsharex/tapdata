package io.tapdata.http.entity;

import io.tapdata.entity.utils.DataMap;
import io.tapdata.http.util.ListUtil;
import io.tapdata.http.util.Tags;
import io.tapdata.pdk.apis.context.TapConnectionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author GavinXiao
 * @description ConnectionConfig create by Gavin
 * @create 2023/5/17 17:20
 **/
public class ConnectionConfig {
    public static final String EVENT_FUNCTION_NAME = "handleEvent";
    private String tableName;
    private String hookUrl;
    private String script;
    private String originalScript;
    private Boolean handleType;
    private List<Object> testRunData;

    public static ConnectionConfig create(TapConnectionContext context) {
        DataMap config = context.getConnectionConfig();
        return new ConnectionConfig()
                .tableName(config.getString("tableName"))
                .hookUrl(config.getString("hookText"))
                .script(config.getString("eventScript"))
                .handleType(config.getValue("handleType", false))
                .testRunData(config.getString("testRunData"));
    }

    public static ConnectionConfig create(Map<?, ?> connectionConfig) {
        Object type = connectionConfig.get("handleType");
        return new ConnectionConfig()
                .tableName((String) connectionConfig.get("tableName"))
                .hookUrl((String) connectionConfig.get("hookText"))
                .script((String) connectionConfig.get("eventScript"))
                .handleType(null != type && (boolean) type);
    }

    public ConnectionConfig testRunData(List<Object> testRunData) {
        this.testRunData = testRunData;
        return this;
    }

    public ConnectionConfig testRunData(Object testRunData) {
        this.testRunData = ListUtil.addObjToList(new ArrayList<>(), testRunData);
        return this;
    }

    public List<Object> testRunData() {
        return this.testRunData;
    }

    public ConnectionConfig tableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String tableName() {
        return this.tableName;
    }

    public ConnectionConfig hookUrl(String hookUrl) {
        this.hookUrl = hookUrl;
        return this;
    }

    public String hookUrl() {
        return this.hookUrl;
    }

    public boolean handleType() {
        return null != handleType && handleType;
    }

    public ConnectionConfig handleType(Boolean handleType) {
        this.handleType = null != handleType && handleType;
        return this;
    }

    public ConnectionConfig script(String script) {
        this.script = Tags.script(script);
        this.originalScript = script;
        return this;
    }

    public String script() {
        return this.script;
    }

    public String originalScript() {
        return this.originalScript;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getHookUrl() {
        return hookUrl;
    }

    public void setHookUrl(String hookUrl) {
        this.hookUrl = hookUrl;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }
}
