
package ai.chat2db.community.domain.api.model.datasource;
import java.util.Objects;

import lombok.Data;


@Data
public class SSHInfo {




    private boolean use;




    private String hostName;




    private String port;




    private String userName;




    private String localPort;




    private String authenticationType;




    private String password;




    private String keyFile;




    private String passphrase;




    private String rHost;




    private String rPort;

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (o == null || getClass() != o.getClass()) {return false;}
        SSHInfo sshInfo = (SSHInfo)o;
        return use == sshInfo.use && Objects.equals(hostName, sshInfo.hostName) && Objects.equals(port,
            sshInfo.port) && Objects.equals(userName, sshInfo.userName) && Objects.equals(localPort,
            sshInfo.localPort) && Objects.equals(authenticationType, sshInfo.authenticationType)
            && Objects.equals(password, sshInfo.password) && Objects.equals(keyFile, sshInfo.keyFile)
            && Objects.equals(passphrase, sshInfo.passphrase) && Objects.equals(rHost, sshInfo.rHost)
            && Objects.equals(rPort, sshInfo.rPort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(use, hostName, port, userName, localPort, authenticationType, password, keyFile, passphrase,
            rHost, rPort);
    }

    @Override
    public String toString() {
        // Exclude password/passphrase/keyFile so SSH connection/port-forwarding error
        // logs do not leak credentials. Lombok @Data would otherwise include them.
        return "SSHInfo(use=" + use + ", hostName=" + hostName + ", port=" + port
                + ", userName=" + userName + ", localPort=" + localPort
                + ", authenticationType=" + authenticationType
                + ", rHost=" + rHost + ", rPort=" + rPort + ")";
    }
}
