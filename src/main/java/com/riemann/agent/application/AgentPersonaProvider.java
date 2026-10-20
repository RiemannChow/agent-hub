package com.riemann.agent.application;

import com.riemann.agent.domain.AgentPersona;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 application.yml 的 {@code agent.persona.*} 读取并构建 AgentPersona。
 * 切换行业 = 改这里的配置（或换成从数据库读取）。
 */
@Component
@ConfigurationProperties(prefix = "agent.persona")
public class AgentPersonaProvider {

    private String roleName = "通用数据分析助手";
    private String domainDescription = "你擅长基于 SeekDB 中的业务数据进行问数分析。";
    private String outputFormat = "以 Markdown 表格返回，并附一句话结论。";

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getDomainDescription() { return domainDescription; }
    public void setDomainDescription(String domainDescription) { this.domainDescription = domainDescription; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    /** 构建领域层 AgentPersona。 */
    public AgentPersona getPersona() {
        return new AgentPersona(roleName, domainDescription, outputFormat);
    }
}
