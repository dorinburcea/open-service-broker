package com.swisscom.cloud.sb.broker.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.swisscom.cloud.sb.broker.cfapi.dto.ProvisioningDto
import com.swisscom.cloud.sb.broker.context.ServiceContextPersistenceService
import com.swisscom.cloud.sb.broker.error.ErrorCode
import com.swisscom.cloud.sb.broker.model.CFService
import com.swisscom.cloud.sb.broker.model.DeprovisionRequest
import com.swisscom.cloud.sb.broker.model.Plan
import com.swisscom.cloud.sb.broker.model.ProvisionRequest
import com.swisscom.cloud.sb.broker.model.ServiceInstance
import com.swisscom.cloud.sb.broker.model.repository.CFServiceRepository
import com.swisscom.cloud.sb.broker.model.repository.PlanRepository
import com.swisscom.cloud.sb.broker.model.repository.ServiceInstanceRepository
import com.swisscom.cloud.sb.broker.provisioning.DeprovisionResponse
import com.swisscom.cloud.sb.broker.provisioning.ProvisionResponse
import com.swisscom.cloud.sb.broker.provisioning.ProvisionResponseDto
import com.swisscom.cloud.sb.broker.provisioning.ProvisioningPersistenceService
import com.swisscom.cloud.sb.broker.provisioning.ProvisioningService
import com.swisscom.cloud.sb.broker.provisioning.lastoperation.LastOperationResponseDto
import com.swisscom.cloud.sb.broker.provisioning.lastoperation.LastOperationStatusService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.servicebroker.model.CloudFoundryContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

import javax.validation.Valid

@Api(value = "Service provisioning", description = "Endpoint for provisioning/deprovisoning")
@RestController
@CompileStatic
@Slf4j
class ProvisioningController extends BaseController {
    public static final String PARAM_ACCEPTS_INCOMPLETE = 'accepts_incomplete'

    @Autowired
    private ProvisioningService provisioningService
    @Autowired
    private ProvisioningPersistenceService provisioningPersistenceService
    @Autowired
    private LastOperationStatusService lastOperationStatusService
    @Autowired
    private ServiceInstanceRepository serviceInstanceRepository
    @Autowired
    private ServiceContextPersistenceService serviceContextService
    @Autowired
    private CFServiceRepository cfServiceRepository
    @Autowired
    private PlanRepository planRepository

    @ApiOperation(value = "Provision a new service instance", response = ProvisionResponseDto.class)
    @RequestMapping(value = '/v2/service_instances/{instanceId}', method = RequestMethod.PUT)
    ResponseEntity<ProvisionResponseDto> provision(@PathVariable("instanceId") String serviceInstanceGuid,
                                                   @RequestParam(value = 'accepts_incomplete', required = false) boolean acceptsIncomplete,
                                                   @Valid @RequestBody ProvisioningDto provisioningDto) {

        log.info("Provision request for ServiceInstanceGuid:${serviceInstanceGuid}, ServiceId: ${provisioningDto?.service_id}, Params: ${provisioningDto.parameters}")

        failIfServiceInstanceAlreadyExists(serviceInstanceGuid)
        log.trace("ProvisioningDto:${provisioningDto.toString()}")

        ProvisionResponse provisionResponse = provisioningService.provision(createProvisionRequest(serviceInstanceGuid, provisioningDto, acceptsIncomplete))

        return new ResponseEntity<ProvisionResponseDto>(new ProvisionResponseDto(dashboard_url: provisionResponse.dashboardURL),
                provisionResponse.isAsync ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
    }

    private ProvisionRequest createProvisionRequest(String serviceInstanceGuid, ProvisioningDto provisioning, boolean acceptsIncomplete) {
        getAndCheckService(provisioning.service_id)

        ProvisionRequest provisionRequest = new ProvisionRequest()
        provisionRequest.serviceInstanceGuid = serviceInstanceGuid
        provisionRequest.plan = getAndCheckPlan(provisioning.plan_id)
        provisionRequest.acceptsIncomplete = acceptsIncomplete
        provisionRequest.parameters = serializeJson(provisioning.parameters)

        if (!provisioning.context && (provisioning.organization_guid && provisioning.space_guid)) {
            provisioning.context = new CloudFoundryContext(provisioning.organization_guid, provisioning.space_guid)
        }

        provisionRequest.serviceContext = serviceContextService.findOrCreate(provisioning.context)

        return provisionRequest
    }

    private static String serializeJson(Object object) {
        if (!object) return null
        return new ObjectMapper().writeValueAsString(object)
    }

    private ServiceInstance failIfServiceInstanceAlreadyExists(String serviceInstanceGuid) {
        ServiceInstance instance = serviceInstanceRepository.findByGuid(serviceInstanceGuid)
        if (instance) {
            log.debug "CFService instance with id ${instance.guid} already exists - returning 409 CONFLICT"
            ErrorCode.SERVICE_INSTANCE_ALREADY_EXISTS.throwNew()
        }
        return instance
    }

    private CFService getAndCheckService(String serviceGuid) {
        CFService cfService = cfServiceRepository.findByGuid(serviceGuid)
        if (!cfService) {
            log.debug("Service  with Guid:${serviceGuid} does not exist")
            ErrorCode.SERVICE_NOT_FOUND.throwNew("requested id:${serviceGuid}")
        }
        return cfService
    }

    private Plan getAndCheckPlan(String planGuid) {
        Plan plan = planRepository.findByGuid(planGuid)
        if (!plan) {
            log.debug("Plan  with Guid:${planGuid} does not exist")
            ErrorCode.PLAN_NOT_FOUND.throwNew("requested id:${planGuid}")
        }
        return plan
    }

    @ApiOperation(value = "Deprovision a service instance")
    @RequestMapping(value = '/v2/service_instances/{instanceId}', method = RequestMethod.DELETE)
    ResponseEntity<String> deprovision(@PathVariable("instanceId") String serviceInstanceGuid,
                                       @RequestParam(value = "accepts_incomplete", required = false) boolean acceptsIncomplete) {
        log.info("Deprovision request for ServiceInstanceGuid: ${serviceInstanceGuid}")
        DeprovisionResponse response = provisioningService.deprovision(createDeprovisionRequest(serviceInstanceGuid, acceptsIncomplete))
        return new ResponseEntity<String>("{}", response.isAsync ? HttpStatus.ACCEPTED : HttpStatus.OK)
    }

    private DeprovisionRequest createDeprovisionRequest(String serviceInstanceGuid, boolean acceptsIncomplete) {
        return new DeprovisionRequest(serviceInstanceGuid: serviceInstanceGuid, serviceInstance: super.getAndCheckServiceInstance(serviceInstanceGuid), acceptsIncomplete: acceptsIncomplete)
    }

    @ApiOperation(value = "Get the last operation status", response = LastOperationResponseDto.class,
            notes = "Returns the last operation status for the given service instance")
    @RequestMapping(value = "/v2/service_instances/{instanceId}/last_operation", method = RequestMethod.GET)
    LastOperationResponseDto lastOperation(@PathVariable("instanceId") String serviceInstanceGuid) {
        return lastOperationStatusService.pollJobStatus(serviceInstanceGuid)
    }

    @RequestMapping(value = "/v2/service_instances/{instanceId}", method = RequestMethod.PATCH)
    ResponseEntity<?> updateServiceInstance(@PathVariable("instanceId") String serviceInstanceId) {
        ErrorCode.SERVICE_UPDATE_NOT_ALLOWED.throwNew()
        return new ResponseEntity<Object>(HttpStatus.UNPROCESSABLE_ENTITY)
    }
}