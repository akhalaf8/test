package com.freightos.integration.contextManager;

import java.util.List;

import javax.servlet.ServletContextEvent;

import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.freightos.integration.api.authorization.AuthorizationServicesFactory;
import com.freightos.integration.api.authorization.xmlbased.XmlConfigAuthorizationService;
import com.freightos.integration.common.Logger;
import com.freightos.integration.exceptions.CodeAndMessage;
import com.freightos.integration.exceptions.GCamelRuntimeException;
import com.freightos.integration.routeManager.RouteBuilderFactory;
import com.freightos.integration.serviceProfileManager.ServiceProfileManager.ProfileType;
import com.freightos.integration.serviceProfileManager.serviceProfilePOJO.ServiceProfileType;

public class ContextInitializer extends GGContextInitializer {

	protected int age;
	private static final Logger logger = new Logger(ContextInitializer.class);
	private RouteBuilderFactory routeFactory;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		super.contextInitialized(sce);
		this.routeFactory = getCurrentWebApplicationContext().getBean(RouteBuilderFactory.class);
		AuthorizationServicesFactory.initializeAuthorizationService(new XmlConfigAuthorizationService(this.serviceProfileManager.getResourcesPath()));
		
		// Build APIs Profiles Routes
		initializeProfiles(sce, ProfileType.SERVICE);

		// Build Service Profile Routes
		initializeProfiles(sce, ProfileType.API);
	}

	private void initializeProfiles(final ServletContextEvent sce, final ProfileType profileType) {
		
		try {
			String contextName = sce.getServletContext().getContextPath();

			if (contextName.startsWith("/")) {
				contextName = contextName.substring(1);
			}
			final String finalContextName = contextName;

			List<String> profiles = this.serviceProfileManager.getProfiles(profileType, contextName);
			
			if(CollectionUtils.isNotEmpty(profiles)) {

				for (final String profileLine : profiles) {
	
					String[] profile = profileLine.split("\\,\\s*");
					String profileContext = null;
					String profileName = null;
	
					if (profile.length == 1) {
						profileName = profile[0];
					} else {
						profileContext = profile[0];
						profileName = profile[1];
					}
	
					// does not belong to this context
					if (StringUtils.isNotBlank(profileContext) && !profileContext.equalsIgnoreCase(contextName)) {
						continue;
					}
					SpringCamelContext context = new SpringCamelContext();
					addCamelContext(sce, context);
	
					context.setApplicationContext(getCurrentWebApplicationContext());
					context.setName(profileName.substring(0, profileName.length() - 4));
					context.getShutdownStrategy().setTimeout(2);
					// this may cause extra memory consumption, but we use it for
					// monitoring
					context.setAllowUseOriginalMessage(true);
	
					final String finalProfileName = profileName;
					final ServiceProfileType serviceProfile = serviceProfileManager.loadProfile(profileType, profileName);
	
					context.addRoutes(new RoutesBuilder() {
						@Override
						public void addRoutesToCamelContext(CamelContext context) throws Exception {
	
							RouteBuilder buildRoute = getRouteBuilder(context, serviceProfile);
	
							if (buildRoute != null) {
								logger.info(
										finalContextName + "," + finalProfileName + ": " + buildRoute.getClass().getName());
								context.addRoutes(buildRoute);
							} else {
								logger.error("RouteBuilder=null for profile=" + finalProfileName);
							}
						}
					});
					context.start();
				}
			}
			else {
				logger.warn("No " + profileType.name() + " profiles found for " + sce.getServletContext().getContextPath());
			}
		} catch (Exception e) {
			throw new GCamelRuntimeException(CodeAndMessage.PROFILE_INITIALIZATION_ERROR, e.getMessage());
		}
	}

	/**
	 * Modules with their own context init, can extend this class and override
	 * this to use their own RouteBuilders
	 */
	private RouteBuilder getRouteBuilder(CamelContext context, ServiceProfileType profile) {
		return routeFactory.getRouteBuilder(profile, context);
	}
}
