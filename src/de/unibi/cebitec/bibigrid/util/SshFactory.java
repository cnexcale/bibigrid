/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package de.unibi.cebitec.bibigrid.util;

import com.amazonaws.services.ec2.model.Instance;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import de.unibi.cebitec.bibigrid.model.Configuration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SshFactory {

    public static final Logger log = LoggerFactory.getLogger(SshFactory.class);

    public static Session createNewSshSession(JSch ssh, String dns, String user, Path identity) {
        try {
            Session sshSession = ssh.getSession(user, dns, 22);
            ssh.addIdentity(identity.toString());

            UserInfo userInfo = new UserInfo() {

                @Override
                public String getPassphrase() {
                    return null;
                }

                @Override
                public String getPassword() {
                    return null;
                }

                @Override
                public boolean promptPassword(String string) {
                    return false;
                }

                @Override
                public boolean promptPassphrase(String string) {
                    return false;
                }

                @Override
                public boolean promptYesNo(String string) {
                    return true;
                }

                @Override
                public void showMessage(String string) {
                    log.info("SSH: {}", string);
                }
            };
            sshSession.setUserInfo(userInfo);
            return sshSession;
        } catch (JSchException e) {
            log.error(e.getMessage());
            return null;
        }
    }

    public static String buildSshCommand(String asGroupName, Configuration cfg, Instance masterInstance) {
        StringBuilder sb = new StringBuilder();
        sb.append("sudo sed -i s/MASTER_IP/$(hostname)/g /etc/ganglia/gmond.conf\n");
        sb.append("sudo service gmetad restart \n");
        sb.append("sudo service ganglia-monitor restart \n");
        sb.append("qconf -as $(hostname)\n");
        if (cfg.getShellScriptFile() != null) {
            try {
                List<String> lines = Files.readAllLines(cfg.getShellScriptFile(), StandardCharsets.UTF_8);
                sb.append("cat > shellscript.sh << EOFCUSTOMSCRIPT \n");

                for (String e : lines) {
                    sb.append(e);
                    sb.append("\n");
                }

                sb.append("\nEOFCUSTOMSCRIPT\n");
                sb.append("bash shellscript.sh &> shellscript.log &\n");
            } catch (IOException e) {
                log.info("Shell script could not be read.");
            }
        }
        if (cfg.isUseMasterAsCompute()) {
            sb.append("./add_exec ");
            sb.append(masterInstance.getPrivateDnsName());
            sb.append(" ");
            sb.append(cfg.getMasterInstanceType().getSpec().instanceCores);
            sb.append("\n");
            sb.append("sudo service gridengine-exec start\n");
        }

        sb.append("echo CONFIGURATION_FINISHED \n");
        return sb.toString();
    }

    public static String buildSshCommand(String asGroupName, Configuration cfg, Instance masterInstance, List<Instance> slaveInstances) {
        StringBuilder sb = new StringBuilder();

        sb.append("sudo sed -i s/MASTER_IP/$(hostname)/g /etc/ganglia/gmond.conf\n");

        sb.append("qconf -as $(hostname)\n");
        if (cfg.getShellScriptFile() != null) {
            try {
                List<String> lines = Files.readAllLines(cfg.getShellScriptFile(), StandardCharsets.UTF_8);
                sb.append("cat > shellscript.sh << EOFCUSTOMSCRIPT \n");

                for (String e : lines) {
                    sb.append(e);
                    sb.append("\n");
                }
                sb.append("\nEOFCUSTOMSCRIPT\n");
                sb.append("bash shellscript.sh &> shellscript.log &\n");
            } catch (IOException e) {
                log.info("Shell script could not be read.");
            }
        }
        if (cfg.isUseMasterAsCompute()) {
            sb.append("./add_exec ");
            sb.append(masterInstance.getPrivateDnsName());
            sb.append(" ");
            sb.append(cfg.getMasterInstanceType().getSpec().instanceCores);
            sb.append("\n");
            sb.append("sudo service gridengine-exec start\n");
        }
        for (Instance instance : slaveInstances) {
            sb.append("./add_exec ");
            sb.append(instance.getPrivateDnsName());
            sb.append(" ");
            sb.append(cfg.getSlaveInstanceType().getSpec().instanceCores);
            sb.append("\n");
        }
        sb.append("sudo service gmetad restart \n");
        sb.append("sudo service ganglia-monitor restart \n");
        sb.append("echo CONFIGURATION_FINISHED \n");
        return sb.toString();
    }

    public static String buildSshCommandOpenstack(String asGroupName, Configuration cfg, String masterDNS) {
        StringBuilder sb = new StringBuilder();
        sb.append("sudo sed -i s/MASTER_IP/$(hostname)/g /etc/ganglia/gmond.conf\n");
        sb.append("sudo service gmetad restart \n");
        sb.append("sudo service ganglia-monitor restart \n");
//        sb.append("sleep 60 \n"); //testweise @TODO
        sb.append("qconf -as $(hostname)\n");
        if (cfg.getShellScriptFile() != null) {
            try {
                List<String> lines = Files.readAllLines(cfg.getShellScriptFile(), StandardCharsets.UTF_8);
                sb.append("cat > shellscript.sh << EOFCUSTOMSCRIPT \n");

                for (String e : lines) {
                    sb.append(e);
                    sb.append("\n");
                }

                sb.append("\nEOFCUSTOMSCRIPT\n");
                sb.append("bash shellscript.sh &> shellscript.log &\n");
            } catch (IOException e) {
                log.info("Shell script could not be read.");
            }
        }
        if (cfg.isUseMasterAsCompute()) {
            sb.append("./add_exec ");
            sb.append(masterDNS);
            sb.append(" ");
            sb.append(cfg.getMasterInstanceType().getSpec().instanceCores);
            sb.append("\n");
            sb.append("sudo service gridengine-exec start\n");
        }

        sb.append("echo CONFIGURATION_FINISHED \n");
        return sb.toString();
    }

    public static String buildSshCommandOpenstack(String asGroupName, Configuration cfg, String masterDNS, List<String> slaveDnsAddresses) {
        StringBuilder sb = new StringBuilder();
//        sb.append("sleep 60 \n"); // @TODO
        sb.append("sudo sed -i s/MASTER_IP/$(hostname)/g /etc/ganglia/gmond.conf\n");
        sb.append("qconf -as $(hostname)\n");
        if (cfg.getShellScriptFile() != null) {
            try {
                List<String> lines = Files.readAllLines(cfg.getShellScriptFile(), StandardCharsets.UTF_8);
                sb.append("cat > shellscript.sh << EOFCUSTOMSCRIPT \n");

                for (String e : lines) {
                    sb.append(e);
                    sb.append("\n");
                }
                sb.append("\nEOFCUSTOMSCRIPT\n");
                sb.append("bash shellscript.sh &> shellscript.log &\n");
            } catch (IOException e) {
                log.info("Shell script could not be read.");
            }
        }
        if (cfg.isUseMasterAsCompute()) {
            sb.append("./add_exec ");
            sb.append(masterDNS);
            sb.append(" ");
            sb.append(cfg.getMasterInstanceType().getSpec().instanceCores);
            sb.append("\n");
            sb.append("sudo service gridengine-exec start\n");
        }
        for (String slaveDNS : slaveDnsAddresses) {
            sb.append("./add_exec ");
            sb.append(slaveDNS);
            sb.append(" ");
            sb.append(cfg.getSlaveInstanceType().getSpec().instanceCores);
            sb.append("\n");
        }
        sb.append("sudo service gmetad restart \n");
        sb.append("sudo service ganglia-monitor restart \n");
        sb.append("echo CONFIGURATION_FINISHED \n");
        return sb.toString();
    }
}
