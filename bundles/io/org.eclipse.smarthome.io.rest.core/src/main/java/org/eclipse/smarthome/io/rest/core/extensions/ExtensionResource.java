/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.io.rest.core.extensions;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.smarthome.core.auth.Role;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.events.Event;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.extension.Extension;
import org.eclipse.smarthome.core.extension.ExtensionEventFactory;
import org.eclipse.smarthome.core.extension.ExtensionService;
import org.eclipse.smarthome.core.extension.ExtensionType;
import org.eclipse.smarthome.io.rest.LocaleUtil;
import org.eclipse.smarthome.io.rest.SatisfiableRESTResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * This class acts as a REST resource for extensions and provides methods to install and uninstall them.
 *
 * @author Kai Kreuzer - Initial contribution and API
 */
@Path(ExtensionResource.PATH_EXTENSIONS)
@RolesAllowed({ Role.ADMIN })
@Api(value = ExtensionResource.PATH_EXTENSIONS)
public class ExtensionResource implements SatisfiableRESTResource {

    private static final String THREAD_POOL_NAME = "extensionService";

    public static final String PATH_EXTENSIONS = "extensions";

    private final Logger logger = LoggerFactory.getLogger(ExtensionResource.class);

    private Set<ExtensionService> extensionServices = new CopyOnWriteArraySet<>();

    private EventPublisher eventPublisher;

    protected void addExtensionService(ExtensionService featureService) {
        this.extensionServices.add(featureService);
    }

    protected void removeExtensionService(ExtensionService featureService) {
        this.extensionServices.remove(featureService);
    }

    protected void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    protected void unsetEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = null;
    }

    @Context
    UriInfo uriInfo;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get all extensions.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public List<Extension> getExtensions(
            @HeaderParam("Accept-Language") @ApiParam(value = "language") String language) {
        logger.debug("Received HTTP GET request at '{}'", uriInfo.getPath());
        Locale locale = LocaleUtil.getLocale(language);
        return getAllExtensions(locale);
    }

    @GET
    @Path("/types")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get all extension types.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Set<ExtensionType> getTypes(@HeaderParam("Accept-Language") @ApiParam(value = "language") String language) {
        logger.debug("Received HTTP GET request at '{}'", uriInfo.getPath());
        Locale locale = LocaleUtil.getLocale(language);
        return getAllExtensionTypes(locale);
    }

    @GET
    @Path("/{extensionId: [a-zA-Z_0-9-]*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get extension with given ID.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK"), @ApiResponse(code = 404, message = "Not found") })
    public Response getById(@HeaderParam("Accept-Language") @ApiParam(value = "language") String language,
            @PathParam("extensionId") @ApiParam(value = "extension ID", required = true) String extensionId) {
        logger.debug("Received HTTP GET request at '{}'.", uriInfo.getPath());
        Locale locale = LocaleUtil.getLocale(language);
        ExtensionService extensionService = getExtensionService(extensionId);
        Object responseObject = extensionService.getExtension(extensionId, locale);
        if (responseObject != null) {
            return Response.ok(responseObject).build();
        } else {
            return Response.status(404).build();
        }
    }

    @POST
    @Path("/{extensionId: [a-zA-Z_0-9-]*}/install")
    @ApiOperation(value = "Installs the extension with the given ID.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Response installExtension(
            final @PathParam("extensionId") @ApiParam(value = "extension ID", required = true) String extensionId) {
        ThreadPoolManager.getPool(THREAD_POOL_NAME).submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ExtensionService extensionService = getExtensionService(extensionId);
                    extensionService.install(extensionId);
                } catch (Exception e) {
                    logger.error("Exception while installing extension: {}", e.getMessage());
                    postFailureEvent(extensionId, e.getMessage());
                }
            }

        });
        return Response.ok().build();
    }

    @POST
    @Path("/{extensionId: [a-zA-Z_0-9-]*}/uninstall")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Response uninstallExtension(
            final @PathParam("extensionId") @ApiParam(value = "extension ID", required = true) String extensionId) {
        ThreadPoolManager.getPool(THREAD_POOL_NAME).submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ExtensionService extensionService = getExtensionService(extensionId);
                    extensionService.uninstall(extensionId);
                } catch (Exception e) {
                    logger.error("Exception while uninstalling extension: {}", e.getMessage());
                    postFailureEvent(extensionId, e.getMessage());
                }
            }
        });
        return Response.ok().build();
    }

    private void postFailureEvent(String extensionId, String msg) {
        if (eventPublisher != null) {
            Event event = ExtensionEventFactory.createExtensionFailureEvent(extensionId, msg);
            eventPublisher.post(event);
        }
    }

    @Override
    public boolean isSatisfied() {
        return !extensionServices.isEmpty();
    }

    private List<Extension> getAllExtensions(Locale locale) {
        List<Extension> ret = new ArrayList<>();
        for (ExtensionService extensionService : extensionServices) {
            ret.addAll(extensionService.getExtensions(locale));
        }
        return ret;
    }

    private Set<ExtensionType> getAllExtensionTypes(Locale locale) {
        final Collator coll = Collator.getInstance(locale);
        coll.setStrength(Collator.PRIMARY);
        Set<ExtensionType> ret = new TreeSet<>(new Comparator<ExtensionType>() {
            @Override
            public int compare(ExtensionType o1, ExtensionType o2) {
                return coll.compare(o1.getLabel(), o2.getLabel());
            }
        });
        for (ExtensionService extensionService : extensionServices) {
            ret.addAll(extensionService.getTypes(locale));
        }
        return ret;
    }

    private ExtensionService getExtensionService(final String extensionId) {
        for (ExtensionService extensionService : extensionServices) {
            for (Extension extension : extensionService.getExtensions(Locale.getDefault())) {
                if (extensionId.equals(extension.getId())) {
                    return extensionService;
                }
            }
        }
        throw new IllegalArgumentException("No extension service registered for " + extensionId);
    }

}
