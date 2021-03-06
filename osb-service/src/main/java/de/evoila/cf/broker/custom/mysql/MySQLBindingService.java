/**
 * 
 */
package de.evoila.cf.broker.custom.mysql;

import de.evoila.cf.broker.bean.ExistingEndpointBean;
import de.evoila.cf.broker.exception.ServiceBrokerException;
import de.evoila.cf.broker.model.*;
import de.evoila.cf.broker.repository.BindingRepository;
import de.evoila.cf.broker.repository.RouteBindingRepository;
import de.evoila.cf.broker.repository.ServiceDefinitionRepository;
import de.evoila.cf.broker.repository.ServiceInstanceRepository;
import de.evoila.cf.broker.service.HAProxyService;
import de.evoila.cf.broker.service.impl.BindingServiceImpl;
import de.evoila.cf.broker.util.RandomString;
import de.evoila.cf.broker.util.ServiceInstanceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Johannes Hiemer.
 *
 */
@Service
public class MySQLBindingService extends BindingServiceImpl {

    private Logger log = LoggerFactory.getLogger(getClass());

    private static String URI = "uri";
    private static String USERNAME = "user";
    private static String DATABASE = "database";
    private static String NAME = "name";

    private RandomString usernameRandomString = new RandomString(10);
    private RandomString passwordRandomString = new RandomString(15);

    private ExistingEndpointBean existingEndpointBean;

	private MySQLCustomImplementation mysqlCustomImplementation;

    public MySQLBindingService(BindingRepository bindingRepository, ServiceDefinitionRepository serviceDefinitionRepository, ServiceInstanceRepository serviceInstanceRepository,
                               RouteBindingRepository routeBindingRepository, HAProxyService haProxyService, ExistingEndpointBean existingEndpointBean, MySQLCustomImplementation mySQLCustomImplementation) {
        super(bindingRepository, serviceDefinitionRepository, serviceInstanceRepository, routeBindingRepository, haProxyService);
        this.existingEndpointBean = existingEndpointBean;
        this.mysqlCustomImplementation = mySQLCustomImplementation;
    }

    @Override
    public ServiceInstanceBinding getServiceInstanceBinding(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected RouteBinding bindRoute(ServiceInstance serviceInstance, String route) {
        throw new UnsupportedOperationException();
    }

	@Override
    protected Map<String, Object> createCredentials(String bindingId, ServiceInstanceBindingRequest serviceInstanceBindingRequest,
                                                    ServiceInstance serviceInstance, Plan plan, ServerAddress host) throws ServiceBrokerException {
        MySQLDbService jdbcService = this.mysqlCustomImplementation.connection(serviceInstance, plan);

		String username = usernameRandomString.nextString();
		String password = passwordRandomString.nextString();
		String database = MySQLUtils.dbName(serviceInstance.getId());

		try {
			mysqlCustomImplementation.bindRoleToDatabase(jdbcService, username, password, database);
		} catch (SQLException e) {
			log.error(e.toString());
			throw new ServiceBrokerException("Could not update Database to create Service Binding");
		}

        List<ServerAddress> serverAddresses = null;
        if (plan.getPlatform() == Platform.BOSH && plan.getMetadata() != null) {
            if (plan.getMetadata().getIngressInstanceGroup() != null && host == null)
                serverAddresses = ServiceInstanceUtils.filteredServerAddress(serviceInstance.getHosts(),
                        plan.getMetadata().getIngressInstanceGroup());
            else if (plan.getMetadata().getIngressInstanceGroup() == null)
                serverAddresses = serviceInstance.getHosts();
        } else if (plan.getPlatform() == Platform.EXISTING_SERVICE) {
            serverAddresses = existingEndpointBean.getHosts();
        } else if (host != null)
            serverAddresses = Arrays.asList(new ServerAddress("service-key-haproxy", host.getIp(), host.getPort()));

        if (serverAddresses == null || serverAddresses.size() == 0)
            throw new ServiceBrokerException("Could not find any Service Backends to create Service Binding");

        String endpoint = ServiceInstanceUtils.connectionUrl(serverAddresses);

        // This needs to be done here and can't be generalized due to the fact that each backend
        // may have a different URL setup
        Map<String, Object> configurations = new HashMap<>();
        configurations.put(URI, String.format("mysql://%s:%s@%s/%s", username, password, endpoint, database));
        configurations.put(DATABASE, database);
        configurations.put(NAME, database);

        Map<String, Object> credentials = ServiceInstanceUtils.bindingObject(serviceInstance.getHosts(),
                username,
                password,
                configurations);

        return credentials;
	}

	@Override
	protected void unbindService(ServiceInstanceBinding binding, ServiceInstance serviceInstance, Plan plan) throws ServiceBrokerException {
		MySQLDbService jdbcService;
		jdbcService = this.mysqlCustomImplementation.connection(serviceInstance, plan);

		if (jdbcService == null)
			throw new ServiceBrokerException("Could not connect to database");

		try {
			mysqlCustomImplementation.unbindRoleFromDatabase(jdbcService, binding.getCredentials().get(USERNAME).toString());
		} catch (SQLException e) {
			log.error(e.toString());
			throw new ServiceBrokerException("Could not remove from database");
		}
	}

}
