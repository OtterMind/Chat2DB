
package ai.chat2db.spi.ssh;

import java.security.Security;

import ai.chat2db.server.tools.common.exception.ConnectionException;
import ai.chat2db.spi.enums.SSHAuthenticationTypeEnum;
import ai.chat2db.spi.model.SSHInfo;
import cn.hutool.core.net.NetUtil;
import cn.hutool.extra.ssh.JschUtil;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * @author jipengfei
 * @version : SSHSessionManager.java
 */
@Slf4j
public class SSHManager {

    static {
        try {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            JSch.setConfig("kex", JSch.getConfig("kex") + ",diffie-hellman-group1-sha1");
            JSch.setConfig("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa,ssh-dss");
        }catch (Exception e){
            log.error("SSHManager init error",e);
        }
    }

    public static Session getSSHSession(SSHInfo ssh) {
        Session session = null;
        try {
            if (SSHAuthenticationTypeEnum.KEYFILE.name().equals(ssh.getAuthenticationType())) {
                byte[] passphrase = StringUtils.isNotBlank(ssh.getPassphrase()) ? StringUtils.getBytes(
                    ssh.getPassphrase(),
                    "UTF-8") : null;
                session = JschUtil.getSession(ssh.getHostName(), Integer.parseInt(ssh.getPort()), ssh.getUserName(),
                    ssh.getKeyFile(), passphrase);
            } else if (StringUtils.isNotBlank(ssh.getUserName())) {
                session = JschUtil.getSession(ssh.getHostName(), Integer.parseInt(ssh.getPort()), ssh.getUserName(),
                    ssh.getPassword());
            }

        } catch (Exception e) {
            log.info("getSSHSession error,sshinfo:{}",ssh.toString(), e);
            throw new ConnectionException("connection.ssh.error", null, e);
        }
        if (session != null && StringUtils.isNotBlank(ssh.getRHost()) && StringUtils.isNotBlank(ssh.getRPort())) {
            try {
                String[] portForwardingL = session.getPortForwardingL();
                if (portForwardingL != null && portForwardingL.length > 0) {
                    return session;
                }
                int localPort = !StringUtils.isBlank(ssh.getLocalPort()) ? Integer.parseInt(ssh.getLocalPort())
                    : NetUtil.getUsableLocalPort();
                ssh.setLocalPort(String.valueOf(localPort));
                session.setPortForwardingL(localPort, ssh.getRHost(),
                    Integer.parseInt(ssh.getRPort()));
            } catch (Exception e) {
                log.info("getSSHSession setPortForwardingL error,sshinfo:{}",ssh.toString(), e);
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
                throw new ConnectionException("connection.ssh.error", null, e);
            }
        }
        return session;
    }

    public static void close() {
        JschUtil.closeAll();
    }
}