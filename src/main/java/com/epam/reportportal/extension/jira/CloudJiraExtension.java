/*
 * Copyright 2021 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.extension.jira;

import com.epam.reportportal.extension.IntegrationGroupEnum;
import com.epam.reportportal.extension.NamedPluginCommand;
import com.epam.reportportal.extension.PluginCommand;
import com.epam.reportportal.extension.ReportPortalExtensionPoint;
import com.epam.reportportal.extension.common.IntegrationTypeProperties;
import com.epam.reportportal.extension.event.PluginEvent;
import com.epam.reportportal.extension.event.StartLaunchEvent;
import com.epam.reportportal.extension.jira.command.*;
import com.epam.reportportal.extension.jira.command.binary.GetFileCommand;
import com.epam.reportportal.extension.jira.command.connection.TestConnectionCommand;
import com.epam.reportportal.extension.jira.command.utils.CloudJiraClientProvider;
import com.epam.reportportal.extension.jira.command.utils.JIRATicketDescriptionService;
import com.epam.reportportal.extension.jira.event.launch.StartLaunchEventListener;
import com.epam.reportportal.extension.jira.event.plugin.PluginEventHandlerFactory;
import com.epam.reportportal.extension.jira.event.plugin.PluginEventListener;
import com.epam.reportportal.extension.jira.info.impl.PluginInfoProviderImpl;
import com.epam.reportportal.extension.jira.utils.MemoizingSupplier;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import com.epam.ta.reportportal.binary.DataStoreService;
import com.epam.ta.reportportal.dao.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jasypt.util.text.BasicTextEncryptor;
import org.jooq.DSLContext;
import org.pf4j.Extension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
@Extension
public class CloudJiraExtension implements ReportPortalExtensionPoint, DisposableBean {

	public static final String BINARY_DATA_PROPERTIES_FILE_ID = "binary-data.properties";

	private static final String PLUGIN_ID = "JIRA Cloud";

	private final String resourcesDir;

	private final Supplier<Map<String, PluginCommand<?>>> pluginCommandMapping = new MemoizingSupplier<>(this::getCommands);

	private final ObjectMapper objectMapper;
	private final RequestEntityConverter requestEntityConverter;

	private final Supplier<ApplicationListener<PluginEvent>> pluginLoadedListenerSupplier;
	private final Supplier<ApplicationListener<StartLaunchEvent>> startLaunchEventListenerSupplier;

	private final Supplier<CloudJiraClientProvider> cloudJiraClientProviderSupplier;

	private final Supplier<JIRATicketDescriptionService> jiraTicketDescriptionServiceSupplier;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private DSLContext dsl;

	@Autowired
	private IntegrationTypeRepository integrationTypeRepository;

	@Autowired
	private IntegrationRepository integrationRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LaunchRepository launchRepository;

	@Autowired
	private LogRepository logRepository;

	@Autowired
	private TestItemRepository testItemRepository;

	@Autowired
	private BasicTextEncryptor textEncryptor;

	@Autowired
	@Qualifier("attachmentDataStoreService")
	private DataStoreService dataStoreService;

	public CloudJiraExtension(Map<String, Object> initParams) {
		resourcesDir = IntegrationTypeProperties.RESOURCES_DIRECTORY.getValue(initParams).map(String::valueOf).orElse("");
		objectMapper = configureObjectMapper();

		pluginLoadedListenerSupplier = new MemoizingSupplier<>(() -> new PluginEventListener(PLUGIN_ID, new PluginEventHandlerFactory(
				integrationTypeRepository,
				integrationRepository,
				new PluginInfoProviderImpl(resourcesDir, BINARY_DATA_PROPERTIES_FILE_ID)
		)));
		startLaunchEventListenerSupplier = new MemoizingSupplier<>(() -> new StartLaunchEventListener(launchRepository));

		requestEntityConverter = new RequestEntityConverter(objectMapper);

		cloudJiraClientProviderSupplier = new MemoizingSupplier<>(() -> new CloudJiraClientProvider(textEncryptor));

		jiraTicketDescriptionServiceSupplier = new MemoizingSupplier<>(() -> new JIRATicketDescriptionService(logRepository,
				testItemRepository
		));
	}

	protected ObjectMapper configureObjectMapper() {
		ObjectMapper om = new ObjectMapper();
		om.setAnnotationIntrospector(new JacksonAnnotationIntrospector());
		om.configure(MapperFeature.DEFAULT_VIEW_INCLUSION, true);
		om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		om.registerModule(new JavaTimeModule());
		return om;
	}

	@Override
	public Map<String, ?> getPluginParams() {
		Map<String, Object> params = new HashMap<>();
		params.put(ALLOWED_COMMANDS, new ArrayList<>(pluginCommandMapping.get().keySet()));
		return params;
	}

	@Override
	public PluginCommand<?> getCommandToExecute(String commandName) {
		return pluginCommandMapping.get().get(commandName);
	}

	@Override
	public IntegrationGroupEnum getIntegrationGroup() {
		return IntegrationGroupEnum.BTS;
	}

	@PostConstruct
	public void createIntegration() {
		initListeners();
	}

	private void initListeners() {
		ApplicationEventMulticaster applicationEventMulticaster = applicationContext.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
				ApplicationEventMulticaster.class
		);
		applicationEventMulticaster.addApplicationListener(pluginLoadedListenerSupplier.get());
		applicationEventMulticaster.addApplicationListener(startLaunchEventListenerSupplier.get());
	}

	@Override
	public void destroy() {
		removeListeners();
	}

	private void removeListeners() {
		ApplicationEventMulticaster applicationEventMulticaster = applicationContext.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
				ApplicationEventMulticaster.class
		);
		applicationEventMulticaster.removeApplicationListener(pluginLoadedListenerSupplier.get());
		applicationEventMulticaster.removeApplicationListener(startLaunchEventListenerSupplier.get());
	}

	private Map<String, PluginCommand<?>> getCommands() {
		List<NamedPluginCommand<?>> commands = new ArrayList<>();
		commands.add(new TestConnectionCommand(cloudJiraClientProviderSupplier.get()));
		commands.add(new GetIssueFieldsCommand(projectRepository, cloudJiraClientProviderSupplier.get()));
		commands.add(new GetIssueTypesCommand(projectRepository, cloudJiraClientProviderSupplier.get()));
		commands.add(new RetrieveCreationParamsCommand(textEncryptor));
		commands.add(new RetrieveUpdateParamsCommand(textEncryptor));
		commands.add(new PostTicketCommand(projectRepository,
				requestEntityConverter,
				cloudJiraClientProviderSupplier.get(),
				jiraTicketDescriptionServiceSupplier.get(),
				dataStoreService
		));

		final Map<String, PluginCommand<?>> commandMap = commands.stream().collect(Collectors.toMap(NamedPluginCommand::getName, it -> it));

		commandMap.put("getFile", new GetFileCommand(resourcesDir, BINARY_DATA_PROPERTIES_FILE_ID));
		return commandMap;

	}
}
