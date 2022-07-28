package de.unibi.cebitec.bibigrid.core.model;

import com.jcraft.jsch.*;
import de.unibi.cebitec.bibigrid.core.intents.CreateClusterEnvironment;
import de.unibi.cebitec.bibigrid.core.model.Configuration.AnsibleRoles;
import de.unibi.cebitec.bibigrid.core.util.AnsibleResources;
import de.unibi.cebitec.bibigrid.core.util.DeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Wrapper for the {@link Configuration} class with extra fields.
 *
 * @author mfriedrichs(at)techfak.uni-bielefeld.de
 * @author tdilger(at)techfak.uni-bielefeld.de
 */
public final class AnsibleConfig {
    private static final Logger LOG = LoggerFactory.getLogger(AnsibleConfig.class);

    AnsibleConfig() {}

    private static List<Configuration.MountPoint> setMasterMounts(DeviceMapper masterDeviceMapper) {
        List<Configuration.MountPoint> masterMountMap = masterDeviceMapper.getSnapshotIdToMountPoint();
        List<Configuration.MountPoint> masterMounts = new ArrayList<>();
        if (masterMountMap != null && masterMountMap.size() > 0) {
            for (Configuration.MountPoint mountPoint : masterMountMap) {
                Configuration.MountPoint localMountPoint = new Configuration.MountPoint();
                localMountPoint.setSource(masterDeviceMapper.getRealDeviceNameForMountPoint(mountPoint.getTarget()));
                localMountPoint.setTarget(mountPoint.getTarget());
                masterMounts.add(localMountPoint);
            }
        }
        return masterMounts;
    }

    private enum WorkerSpecification {
        BATCH, INDEX, TYPE, IMAGE, PROVIDER_TYPE, NETWORK, SECURITY_GROUP, SERVER_GROUP
    }

    /**
     * Write hosts config file to remote master.
     * @param channel sftp channel to master instance
     * @param sshUser user to use as remote ssh user
     * @param workerInstances list of worker instances
     */
    public static void writeHostsFile(ChannelSftp channel, String sshUser, List<Instance> workerInstances, boolean useHostnames) {
        AnsibleHostsConfig hostsConfig = new AnsibleHostsConfig(sshUser, workerInstances, useHostnames);
        try (OutputStreamWriter writer = new OutputStreamWriter(channel.put(channel.getHome() + "/" +
                AnsibleResources.HOSTS_CONFIG_FILE), StandardCharsets.UTF_8)) {
            writer.write(hostsConfig.toString());
        } catch (SftpException | IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper methods that create a role data structure
     * @param name role name
     * @param tags a list of tags bound to role
     * @return role hashmap
     */
    private static HashMap<String, Object> createRole(String name, List tags) {
        HashMap<String, Object> role = new LinkedHashMap<>();
        role.put("role",name);
        role.put("tags",tags);
        return role;
    }

    /**
     * Generates site.yml automatically including custom ansible roles.
     *
     * @param stream write file to remote
     * @param customMasterRoles master ansible roles names and variable file names
     * @param customWorkerRoles worker ansible roles names and variable file names
     */
    public static void writeSiteFile(OutputStream stream,
                              Map<String, String> customMasterRoles,
                              Map<String, String> customWorkerRoles) {

        List<String> common_vars =
                Arrays.asList(AnsibleResources.LOGIN_YML, AnsibleResources.INSTANCES_YML, AnsibleResources.CONFIG_YML);
        String DEFAULT_IP_FILE = AnsibleResources.VARS_PATH + "{{ ansible_default_ipv4.address }}.yml";

        // master configuration
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("hosts", "master");
        master.put("become", "yes");
        List<String> vars_files = new ArrayList<>(common_vars);
        for (String vars_file : customMasterRoles.values()) {
            if (!vars_file.equals("")) {
                vars_files.add(vars_file);
            }
        }
        master.put("vars_files", vars_files);
        List<Object> roles = new ArrayList<>();
        roles.add("common");
        roles.add("master");
        roles.add(createRole("slurm",Arrays.asList("slurm","scale-up","scale-down")));
        for (String role_name : customMasterRoles.keySet()) {
            roles.add("additional/" + role_name);
        }
        master.put("roles", roles);
        // worker configuration
        Map<String, Object> workers = new LinkedHashMap<>();
        workers.put("hosts", "workers");
        workers.put("become", "yes");
        vars_files = new ArrayList<>(common_vars);
        vars_files.add(DEFAULT_IP_FILE);
        for (String vars_file : customWorkerRoles.values()) {
            if (!vars_file.equals("")) {
                vars_files.add(vars_file);
            }
        }
        workers.put("vars_files", vars_files);
        roles = new ArrayList<>();
        roles.add("common");
        roles.add("worker");
        roles.add(createRole("slurm",Arrays.asList("slurm","scale-up","scale-down")));
        for (String role_name : customWorkerRoles.keySet()) {
            roles.add("additional/" + role_name);
        }
        workers.put("roles", roles);
        YamlInterpreter.writeToOutputStream(stream, Arrays.asList(master, workers));
    }

    /**
     * Writes file for each ansible role to integrate environment variables.
     * @param stream write file to remote
     */
    public static void writeAnsibleVarsFile(OutputStream stream, Map<String, Object> vars) {
        if (vars != null && !vars.isEmpty()) {
            YamlInterpreter.writeToOutputStream(stream, vars);
        }
    }

    /**
     * Generates roles/additional/requirements.yml automatically including roles to install via ansible-galaxy.
     * @param stream write file to remote
     */
    public static void writeRequirementsFile(OutputStream stream, List<Configuration.AnsibleGalaxyRoles> galaxyRoles) {
        List<Map<String, Object>> galaxy_roles = new ArrayList<>();
        List<Map<String, Object>> git_roles = new ArrayList<>();
        List<Map<String, Object>> url_roles = new ArrayList<>();

        for (Configuration.AnsibleGalaxyRoles galaxyRole : galaxyRoles) {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("name", galaxyRole.getName());
            if (galaxyRole.getGalaxy() != null) {
                role.put("src", galaxyRole.getGalaxy());
                galaxy_roles.add(role);
            } else if (galaxyRole.getGit() != null) {
                role.put("src", galaxyRole.getGit());
                git_roles.add(role);
            } else if (galaxyRole.getUrl() != null) {
                role.put("src", galaxyRole.getUrl());
                url_roles.add(role);
            }
        }
        List<Map<String, Object>> roles = new ArrayList<>();
        roles.addAll(galaxy_roles);
        roles.addAll(git_roles);
        roles.addAll(url_roles);
        YamlInterpreter.writeToOutputStream(stream, roles);
    }

    /**
     * Write specified instance to stream (in YAML format)
     */
    public static void writeSpecificInstanceFile(
            OutputStream stream, Instance instance,
            List<Configuration.MountPoint> mountPoints, String blockDeviceBase) {
        YamlInterpreter.writeToOutputStream(stream, getInstanceMap(instance, mountPoints, blockDeviceBase));
    }

    /**
     * Writes login.yml with essential user data.
     * @param stream write into cluster_login.yml
     */
    public static void writeLoginFile(OutputStream stream, Configuration config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", config.getMode());
        map.put("default_user", config.getUser());
        map.put("ssh_user", config.getSshUser());
        map.put("munge_key",config.getMungeKey());
        YamlInterpreter.writeToOutputStream(stream, map);
    }

    /**
     * Write instances.yml with instances information.
     * @param stream write into cluster_instances.yml
     * @param master master instance of cluster
     * @param workers list of worker instances of cluster
     * @param masterDeviceMapper
     * @param blockDeviceBase Block device base path ex. "/dev/xvd" in AWS, "/dev/vd" in Openstack
     * @Todo  Add ephemeral disk value to instances.yml (#333)
     */
    public static void writeInstancesFile(
            OutputStream stream,
            Instance master,
            List<Instance> workers,
            DeviceMapper masterDeviceMapper,
            String blockDeviceBase) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Configuration.MountPoint> masterMounts = setMasterMounts(masterDeviceMapper);
        map.put("master", getMasterMap(master, masterMounts, blockDeviceBase));
        map.put("workers", getWorkerMap(workers, masterMounts, blockDeviceBase));
        // No deleted workers when writing instance file first time, create empty list
        map.put("deletedWorkers", new ArrayList<Map<String, Object>>());
        YamlInterpreter.writeToOutputStream(stream, map);
    }

    /**
     * Writes batch-index, type and image of each worker batch to file.
     * @param specification_stream write into worker_specification.yml
     * @param config specified configuration
     */
    public static void writeWorkerSpecificationFile(OutputStream specification_stream, Configuration config, CreateClusterEnvironment environment) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < config.getWorkerInstances().size(); i++) {
            Configuration.WorkerInstanceConfiguration instanceConfiguration = config.getWorkerInstances().get(i);
            Map<String, Object> batchMap = new LinkedHashMap<>();
            batchMap.put(WorkerSpecification.INDEX.name(), (i + 1));
            batchMap.put(WorkerSpecification.TYPE.name(), instanceConfiguration.getType());
            batchMap.put(WorkerSpecification.IMAGE.name(), instanceConfiguration.getImage());
            batchMap.put(WorkerSpecification.NETWORK.name(), environment.getNetwork().getName());
            map.put(WorkerSpecification.BATCH.name() + " " + (i + 1), batchMap);
            // TODO probably extend to network, securityGroup etc...
        }
        YamlInterpreter.writeToOutputStream(specification_stream, map);
    }

    /**
     * Initialize instanceConfiguration of worker batch.
     * @param in stream to read from worker_specification.yml
     * @return configuration for specified batch
     */
    public static Configuration.WorkerInstanceConfiguration readWorkerSpecificationFile(InputStream in, int batchIndex) {
        Configuration.WorkerInstanceConfiguration instanceConfiguration = null;
        Map<String, Object> map = YamlInterpreter.readFromInputStream(in);
        for (Object val : map.values()) {
            Map<String, Object> batchMap = (Map<String, Object>) val;
            int index = Integer.parseInt(String.valueOf(batchMap.get(WorkerSpecification.INDEX.name())));
            if (index == batchIndex) {
                instanceConfiguration = new Configuration.WorkerInstanceConfiguration();
                instanceConfiguration.setType(String.valueOf(batchMap.get(WorkerSpecification.TYPE.name())));
                instanceConfiguration.setImage(String.valueOf(batchMap.get(WorkerSpecification.IMAGE.name())));
                instanceConfiguration.setNetwork(String.valueOf(batchMap.get(WorkerSpecification.NETWORK.name())));
                break;
            }
        }
        return instanceConfiguration;
    }

    /**
     * Rewrite instances.yml and specific instance IP yaml files.
     * ATTENTION/REMARK:
     * It is necessary to have the correct ssh user in config
     *
     * @param sshSession active session
     * @param config parsed configuration
     * @param cluster representation of cluster with id, instances...
     * @param providerModule specific cloud provider

     * @throws JSchException
     */
    public static void updateAnsibleWorkerLists(
            Session sshSession,
            Configuration config,
            Cluster cluster,
            ProviderModule providerModule) throws JSchException {
        LOG.info("Upload updated Ansible files ...");
        ChannelSftp channel = (ChannelSftp) sshSession.openChannel("sftp");
        channel.connect();
        try {
            rewriteInstancesFile(
                    channel,
                    cluster.getWorkerInstances(),
                    cluster.getDeletedInstances(),
                    config.getMasterMounts(),
                    providerModule.getBlockDeviceBase());
            updateSpecificInstanceFiles(
                    channel, cluster.getWorkerInstances(),
                    config.getMasterMounts(), providerModule.getBlockDeviceBase());
            writeHostsFile(channel, config.getSshUser(), cluster.getWorkerInstances(), config.useHostnames());
            LOG.info("Ansible files successfully updated.");
        } catch (SftpException e) {
            LOG.error("Update may not be finished properly due to an SFTP error.");
            e.printStackTrace();
        } finally {
            channel.disconnect();
        }
    }

    /**
     * Updates specific instance files when scaling up / down.
     * @param channel sftp channel to exchange files
     * @param workerInstances updated worker list after scaling
     * @param blockDeviceBase block device base path for the specific provider implementation ("/dev/vd" in OpenStack)
     * @throws SftpException catched in elder method
     */
    private static void updateSpecificInstanceFiles (
            ChannelSftp channel,
            List<Instance> workerInstances,
            List<Configuration.MountPoint> mountPoints,
            String blockDeviceBase) throws SftpException {
        String vars_dir = channel.getHome() + "/" + AnsibleResources.CONFIG_ROOT_PATH + "/";
        // Remove old specific instance files
        List<String> ip_files = new ArrayList<>();
        channel.cd(vars_dir);
        Vector vars_files = channel.ls("*.yml");
        // Loop over all yml files in vars dir
        for(Object file : vars_files) {
            String filename = ((ChannelSftp.LsEntry) file).getFilename();
            // Check, whether instances yml (<instance-ip>.yml) is belongs to available instance in workerInstances
            if (YamlInterpreter.isIPAddressFile(filename)) {
                ip_files.add(filename);
            }
        }
        for (String ip_file : ip_files) {
            // Remove file if not longer belonging to a worker instance
            channel.rm(ip_file);
        }
        // Write new specific instance files
        for (Instance worker : workerInstances) {
            String filename = vars_dir + worker.getPrivateIp() + ".yml";
            AnsibleConfig.writeSpecificInstanceFile(channel.put(filename), worker, mountPoints, blockDeviceBase);
        }
    }

    /**
     * Loads instances.yml file from remote and adds or removes workers.
     * @param channel sftp channel to exchange files
     * @param workerInstances updated worker list after scaling
     * @param blockDeviceBase block device base path for the specific provider implementation ("/dev/vd" in OpenStack)
     * @throws SftpException catched in elder method
     */
    private static void rewriteInstancesFile(
            ChannelSftp channel,
            List<Instance> workerInstances,
            List<Instance> deletedInstances,
            List<Configuration.MountPoint> masterMounts,
            String blockDeviceBase) throws SftpException {
        String instances_file = channel.getHome() + "/" + AnsibleResources.COMMONS_INSTANCES_FILE;
        InputStream in = channel.get(instances_file);
        Map<String, Object> map = YamlInterpreter.readFromInputStream(in);
        map.replace("workers", getWorkerMap(workerInstances, masterMounts, blockDeviceBase));
        map.replace("deletedWorkers", getWorkerMap(deletedInstances, masterMounts, blockDeviceBase));
        OutputStream out = channel.put(instances_file);
        YamlInterpreter.writeToOutputStream(out, map);
    }

    /**
     * Writes common_config.yml with cluster configuration.
     * @param stream write into cluster_configuration.yml
     */
    public static void writeConfigFile(OutputStream stream, Configuration config, String subnetCidr) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (config.getServiceCIDR() == null) {
            map.put("CIDR", subnetCidr);
        } else {
            map.put("CIDR",config.getServiceCIDR());
        }
        map.put("local_fs", config.getLocalFS().name().toLowerCase(Locale.US));
        addBooleanOption(map, "enable_nfs", config.isNfs());
        addBooleanOption(map, "local_dns_lookup", config.isLocalDNSLookup());
        addBooleanOption(map, "enable_gridengine", config.isOge());
        addBooleanOption(map, "enable_slurm",config.isSlurm());
        addBooleanOption(map, "use_master_as_compute", config.isUseMasterAsCompute());
        addBooleanOption(map, "enable_zabbix", config.isZabbix());
        addBooleanOption(map, "enable_ide", config.isIDE());
        if (config.isNfs()) {
            map.put("nfs_mounts", getNfsSharesMap(config.getNfsShares()));
            map.put("ext_nfs_mounts", getExtNfsSharesMap(config.getExtNfsShares()));
        }
        if (config.isIDE()) {
            map.put("ideConf", getIdeConfMap(config.getIdeConf()));
        }
        if (config.isSlurm()) {
            map.put("slurmConf",getSlurmConfMap(config.getSlurmConf()));
        }
        if (config.isZabbix()) {
            map.put("zabbix", getZabbixConfMap(config.getZabbixConf()));
        }
        if (config.hasCustomAnsibleRoles()) {
            map.put("ansible_roles", getAnsibleRoles(config.getAnsibleRoles()));
        }
        if (config.hasCustomAnsibleGalaxyRoles()) {
            map.put("ansible_galaxy_roles", getAnsibleGalaxyRoles(config.getAnsibleGalaxyRoles()));
        }
        YamlInterpreter.writeToOutputStream(stream, map);
    }

    private static void addBooleanOption(Map<String, Object> map, String option, boolean value) {
        map.put(option, value ? "yes" : "no");
    }

    /**
     * Executes given scripts via exec channel.
     * @param channel channel to execute on remote master
     * @param scripts given ansible-playbook scripts
     * @throws JSchException possible ssh channel failure
     */
    public static void executeAnsiblePlaybookScripts(
            ChannelExec channel,
            List<String> scripts) throws JSchException {
        LOG.info("Execute Ansible scripts ...");
        for (String command : scripts) {
            channel.setCommand(command);
        }
        channel.connect();
    }

    /**
     * Initializes instance map for master instance including mounts.
     * @param masterInstance master
     * @return map of instance specific information
     */
    private static Map<String, Object> getMasterMap(
            Instance masterInstance,
            List<Configuration.MountPoint> masterMounts,
            String blockDeviceBase) {
        Map<String, Object> masterMap = getInstanceMap(masterInstance, masterMounts, blockDeviceBase);
        if (masterMounts != null && masterMounts.size() > 0) {
            List<Map<String, String>> masterMountsMap = new ArrayList<>();
            for (Configuration.MountPoint masterMount : masterMounts) {
                Map<String, String> mountMap = new LinkedHashMap<>();
                mountMap.put("src", masterMount.getSource());
                mountMap.put("dst", masterMount.getTarget());
                masterMountsMap.add(mountMap);
            }
            masterMap.put("disks", masterMountsMap);
        }
        return masterMap;
    }

    /**
     * Provides instance map for all worker instances.
     * @param workers list of workers
     * @return list of maps for each worker instance, empty list if worker list is empty
     */
    private static List<Map<String, Object>> getWorkerMap(
            List<Instance> workers, List<Configuration.MountPoint> masterMounts, String blockDeviceBase) {
        List<Map<String, Object>> workerList = new ArrayList<>();
        for (Instance worker : workers) {
            workerList.add(getInstanceMap(worker, masterMounts, blockDeviceBase));
        }
        return workerList;
    }

    /**
     * Creates map of instance configuration.
     * @param instance current remote instance
     * @return map of instance configuration
     */
    private static Map<String, Object> getInstanceMap(
            Instance instance, List<Configuration.MountPoint> mountPoints, String blockDeviceBase) {
        InstanceType providerType = instance.getConfiguration().getProviderType();
        Map<String, Object> instanceMap = new LinkedHashMap<>();
        instanceMap.put("ip", instance.getPrivateIp());
        instanceMap.put("cores", providerType.getCpuCores());
        instanceMap.put("memory", providerType.getMaxRam());
        instanceMap.put("hostname", instance.getHostname());
        List<HashMap<String, Object>> ephemerals = getEphemeralDevices(providerType, mountPoints, blockDeviceBase);
        if (!ephemerals.isEmpty()) {
            instanceMap.put("ephemerals", ephemerals);
        }
        instanceMap.put("disk_space", providerType.getMaxDiskSpace());
        return instanceMap;
    }

    private static Map<String, Object> getZabbixConfMap(Configuration.ZabbixConf zc) {
        Map<String, Object> zabbixConf = new LinkedHashMap<>();
        zabbixConf.put("db",zc.getDb());
        zabbixConf.put("db_user",zc.getDb_user());
        zabbixConf.put("db_password",zc.getDb_password());
        zabbixConf.put("timezone",zc.getTimezone());
        zabbixConf.put("server_name",zc.getServer_name());
        zabbixConf.put("admin_password",zc.getAdmin_password());
        return zabbixConf;
    }

    private static Map<String, Object> getSlurmConfMap(Configuration.SlurmConf sc) {
        Map<String, Object> slurmConf = new LinkedHashMap<>();
        slurmConf.put("db",sc.getDatabase());
        slurmConf.put("db_user",sc.getDb_user());
        slurmConf.put("db_password",sc.getDb_password());
        return slurmConf;
    }

    private static Map<String, String> getOgeConfMap(Properties oc) {
        Map<String, String> ogeConf = new HashMap<>();
        for (final String name : oc.stringPropertyNames()) {
            ogeConf.put(name, oc.getProperty(name));
        }
        return ogeConf;
    }

    private static Map<String, Object> getIdeConfMap(Configuration.IdeConf ic) {
        Map<String, Object> ideConf = new LinkedHashMap<>();
        ideConf.put("ide", ic.isIde());
        ideConf.put("workspace",ic.getWorkspace());
        ideConf.put("port_start", ic.getPort_start());
        ideConf.put("port_end", ic.getPort_end());
        ideConf.put("build",ic.isBuild());
        return ideConf;
    }

    /**
     * Puts parameter values of every given role into Map list.
     * @return list of roles with single parameters
     */
    private static List<Map<String, Object>> getAnsibleRoles(List<AnsibleRoles> roles) {
        List<Map<String, Object>> ansibleRoles = new ArrayList<>();
        for (AnsibleRoles role : roles) {
            Map<String, Object> roleConf = new LinkedHashMap<>();
            if (role.getName() != null && !role.getName().equals("")) roleConf.put("name", role.getName());
            roleConf.put("file", role.getFile());
            roleConf.put("hosts", role.getHosts());
            if (role.getVars() != null && !role.getVars().isEmpty()) roleConf.put("vars", role.getVars());
            if (role.getVarsFile() != null) roleConf.put("vars_file", role.getVarsFile());
            ansibleRoles.add(roleConf);
        }
        return ansibleRoles;
    }

    /**
     * Puts parameter values of every given ansible-galaxy role into Map list.
     * @return list of roles with single parameters
     */
    private static List<Map<String, Object>> getAnsibleGalaxyRoles(List<Configuration.AnsibleGalaxyRoles> roles) {
        List<Map<String, Object>> ansibleGalaxyRoles = new ArrayList<>();
        for (Configuration.AnsibleGalaxyRoles role : roles) {
            Map<String, Object> roleConf = new LinkedHashMap<>();
            roleConf.put("name", role.getName());
            roleConf.put("hosts", role.getHosts());
            if (role.getGalaxy() != null) roleConf.put("galaxy", role.getGalaxy());
            if (role.getGit() != null) roleConf.put("git", role.getGit());
            if (role.getUrl() != null) roleConf.put("url", role.getUrl());
            if (role.getVars() != null && !role.getVars().isEmpty()) roleConf.put("vars", role.getVars());
            if (role.getVarsFile() != null) roleConf.put("vars_file", role.getVarsFile());
            ansibleGalaxyRoles.add(roleConf);
        }
        return ansibleGalaxyRoles;
    }

    /**
     * @param flavor instanceType / providerType
     * @param blockDeviceBase vda, vdb, ...
     * @return list of ephemerals with device, size and mountpoint
     */
    private static List<HashMap<String, Object>> getEphemeralDevices(
            InstanceType flavor, List<Configuration.MountPoint> mountPoints, String blockDeviceBase) {
        List<HashMap<String, Object>> ephemerals = new ArrayList<>();
        int count = flavor.getEphemerals();
        for (int c = 0; c < count; c++) {
            HashMap<String, Object> ephemeral_map = new HashMap<>();
            String device = blockDeviceBase + (char) ( c + DeviceMapper.BLOCK_DEVICE_START );
            ephemeral_map.put("device", device);
            List<Long> ephemeralDiskSpace = flavor.getEphemeralDiskSpace();
            long size = ( ephemeralDiskSpace == null || ephemeralDiskSpace.isEmpty() ) ? 0 : ephemeralDiskSpace.get(c);
            ephemeral_map.put("size", size);
            // cloud provider specific mounting point
            String mountpoint;
            if (mountPoints.isEmpty()) {
                // TODO hardcoded vol
                mountpoint = "/vol";
            } else {
                mountpoint = mountPoints.get(c).getTarget();
            }
            ephemeral_map.put("mountpoint", mountpoint);
            ephemerals.add(ephemeral_map);
        }
        return ephemerals;
    }

    private static List<Map<String, String>> getNfsSharesMap(List<String> nfsShares) {
        List<Map<String, String>> nfsSharesMap = new ArrayList<>();
        for (String nfsShare : nfsShares) {
            Map<String, String> shareMap = new LinkedHashMap<>();
            shareMap.put("src", nfsShare);
            shareMap.put("dst", nfsShare);
            nfsSharesMap.add(shareMap);
        }
        return nfsSharesMap;
    }

    private static List<Map<String, String>> getExtNfsSharesMap(List<Configuration.MountPoint> extNfsShares) {
        List<Map<String, String>> nfsSharesMap = new ArrayList<>();
        for (Configuration.MountPoint extNfsShare : extNfsShares) {
            Map<String, String> shareMap = new LinkedHashMap<>();
            shareMap.put("src", extNfsShare.getSource());
            shareMap.put("dst", extNfsShare.getTarget());
            nfsSharesMap.add(shareMap);
        }
        return nfsSharesMap;
    }
}
