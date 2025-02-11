/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.cmmn.rest.service.api.runtime.caze;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.api.runtime.PlanItemInstance;
import org.flowable.cmmn.rest.service.api.CmmnRestApiInterceptor;
import org.flowable.cmmn.rest.service.api.CmmnRestResponseFactory;
import org.flowable.cmmn.rest.service.api.engine.variable.RestVariable;
import org.flowable.cmmn.rest.service.api.engine.variable.RestVariable.RestVariableScope;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.rest.exception.FlowableContentNotSupportedException;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Tijs Rademakers
 */
public class BaseVariableResource {

    @Autowired
    protected ObjectMapper objectMapper;
    
    @Autowired
    protected CmmnRuntimeService runtimeService;
    
    @Autowired
    protected CmmnRestResponseFactory restResponseFactory;
    
    @Autowired(required=false)
    protected CmmnRestApiInterceptor restApiInterceptor;
    
    @Autowired
    protected Environment env;
    
    protected boolean isSerializableVariableAllowed;

    @PostConstruct
    protected void postConstruct() {
        isSerializableVariableAllowed = env.getProperty("rest.variables.allow.serializable", Boolean.class, true);
    }
    
    protected CaseInstance getCaseInstanceFromRequest(String caseInstanceId) {
        CaseInstance caseInstance = runtimeService.createCaseInstanceQuery().caseInstanceId(caseInstanceId).singleResult();
        if (caseInstance == null) {
            throw new FlowableObjectNotFoundException("Could not find a case instance with id '" + caseInstanceId + "'.");
        }
        
        if (restApiInterceptor != null) {
            restApiInterceptor.accessCaseInstanceInfoById(caseInstance);
        }
        
        return caseInstance;
    }
    
    protected PlanItemInstance getPlanItemFromRequest(String planItemInstanceId) {
        PlanItemInstance planItemInstance = runtimeService.createPlanItemInstanceQuery().planItemInstanceId(planItemInstanceId).singleResult();
        if (planItemInstance == null) {
            throw new FlowableObjectNotFoundException("Could not find a plan item instance with id '" + planItemInstanceId + "'.");
        }

        if (restApiInterceptor != null) {
            restApiInterceptor.accessPlanItemInstanceInfoById(planItemInstance);
        }

        return planItemInstance;
    }

    public RestVariable getVariableFromRequest(CaseInstance caseInstance, String variableName, int variableType, boolean includeBinary) {
        Object value = null;

        if (caseInstance == null) {
            throw new FlowableObjectNotFoundException("Could not find a case instance", CaseInstance.class);
        }

        value = runtimeService.getVariable(caseInstance.getId(), variableName);

        if (value == null) {
            throw new FlowableObjectNotFoundException("Case instance '" + caseInstance.getId() + "' doesn't have a variable with name: '" + variableName + "'.", VariableInstance.class);
        } else {
            //we use null for the scope, because the extraction from request does not require the scope
            return constructRestVariable(variableName, value, caseInstance.getId(), variableType, includeBinary, null);
        }
    }

    public RestVariable getVariableFromRequest(PlanItemInstance planItemInstance, String variableName, int variableType, boolean includeBinary) {
        Object value = null;

        if (planItemInstance == null) {
            throw new FlowableObjectNotFoundException("Could not find a plan item instance", CaseInstance.class);
        }

        value = runtimeService.getLocalVariable(planItemInstance.getId(), variableName);

        if (value == null) {
            throw new FlowableObjectNotFoundException(
                    "Plan item instance '" + planItemInstance.getId() + "' doesn't have a variable with name: '" + variableName + "'.",
                    VariableInstance.class);
        } else {
            //we use null for the scope, because the extraction from request does not require the scope
            return constructRestVariable(variableName, value, planItemInstance.getId(), variableType, includeBinary, null);
        }
    }

    protected byte[] getVariableDataByteArray(CaseInstance caseInstance, String variableName, int variableType, HttpServletResponse response) {
        RestVariable variable = getVariableFromRequest(caseInstance, variableName, variableType, true);
        return restVariableDataToRestResponse(variable, response);
    }

    protected byte[] getVariableDataByteArray(PlanItemInstance planItemInstance, String variableName, int variableType, HttpServletResponse response) {
        RestVariable variable = getVariableFromRequest(planItemInstance, variableName, variableType, true);
        return restVariableDataToRestResponse(variable, response);
    }

    protected byte[] restVariableDataToRestResponse(RestVariable variable, HttpServletResponse response) {
        byte[] result = null;
        try {
            if (CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variable.getType())) {
                result = (byte[]) variable.getValue();
                response.setContentType("application/octet-stream");

            } else if (CmmnRestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variable.getType())) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ObjectOutputStream outputStream = new ObjectOutputStream(buffer);
                outputStream.writeObject(variable.getValue());
                outputStream.close();
                result = buffer.toByteArray();
                response.setContentType("application/x-java-serialized-object");

            } else {
                throw new FlowableObjectNotFoundException("The variable does not have a binary data stream.", null);
            }
        } catch (IOException ioe) {
            throw new FlowableException("Error getting variable " + variable.getName(), ioe);
        }
        return result;
    }

    protected RestVariable constructRestVariable(String variableName, Object value, String caseInstanceId, int variableType, boolean includeBinary,
            RestVariableScope scope) {
        return restResponseFactory.createRestVariable(variableName, value, scope, caseInstanceId, variableType, includeBinary);
    }

    protected List<RestVariable> processCaseVariables(CaseInstance caseInstance, int variableType) {

        // Check if it's a valid execution to get the variables for
        List<RestVariable> variables = addVariables(caseInstance, variableType);

        // Get unique variables from map
        List<RestVariable> result = new ArrayList<>(variables);
        return result;
    }

    protected Object createVariable(CaseInstance caseInstance, int variableType, HttpServletRequest request, HttpServletResponse response) {

        Object result = null;
        if (request instanceof MultipartHttpServletRequest) {
            result = setBinaryVariable((MultipartHttpServletRequest) request, caseInstance.getId(), variableType, true);
        } else {

            List<RestVariable> inputVariables = new ArrayList<>();
            List<RestVariable> resultVariables = new ArrayList<>();
            result = resultVariables;

            try {
                @SuppressWarnings("unchecked")
                List<Object> variableObjects = (List<Object>) objectMapper.readValue(request.getInputStream(), List.class);
                for (Object restObject : variableObjects) {
                    RestVariable restVariable = objectMapper.convertValue(restObject, RestVariable.class);
                    inputVariables.add(restVariable);
                }
                
            } catch (Exception e) {
                throw new FlowableIllegalArgumentException("Failed to serialize to a RestVariable instance", e);
            }

            if (inputVariables == null || inputVariables.size() == 0) {
                throw new FlowableIllegalArgumentException("Request didn't contain a list of variables to create.");
            }

            Map<String, Object> variablesToSet = new HashMap<>();
            for (RestVariable var : inputVariables) {
                if (var.getName() == null) {
                    throw new FlowableIllegalArgumentException("Variable name is required");
                }

                Object actualVariableValue = restResponseFactory.getVariableValue(var);
                variablesToSet.put(var.getName(), actualVariableValue);
                resultVariables.add(restResponseFactory.createRestVariable(var.getName(), actualVariableValue, RestVariableScope.GLOBAL, caseInstance.getId(), variableType, false));
            }

            if (!variablesToSet.isEmpty()) {
                runtimeService.setVariables(caseInstance.getId(), variablesToSet);
            }
        }
        response.setStatus(HttpStatus.CREATED.value());
        return result;
    }
    
    protected List<RestVariable> addVariables(CaseInstance caseInstance, int variableType) {
        Map<String, Object> rawVariables = runtimeService.getVariables(caseInstance.getId());
        return restResponseFactory.createRestVariables(rawVariables, caseInstance.getId(), variableType);
    }
    
    public void deleteAllVariables(CaseInstance caseInstance, HttpServletResponse response) {
        Collection<String> currentVariables = runtimeService.getVariables(caseInstance.getId()).keySet();
        runtimeService.removeVariables(caseInstance.getId(), currentVariables);

        response.setStatus(HttpStatus.NO_CONTENT.value());
    }
    
    protected RestVariable setSimpleVariable(RestVariable restVariable, String instanceId, boolean isNew, RestVariableScope scope, int variableType) {
        if (restVariable.getName() == null) {
            throw new FlowableIllegalArgumentException("Variable name is required");
        }

        Object actualVariableValue = restResponseFactory.getVariableValue(restVariable);
        setVariable(instanceId, restVariable.getName(), actualVariableValue, scope, isNew);

        return constructRestVariable(restVariable.getName(), actualVariableValue, instanceId, variableType, false, scope);
    }

    protected RestVariable setSimpleVariable(RestVariable restVariable, String instanceId, boolean isNew, int variableType) {
        return setSimpleVariable(restVariable, instanceId, isNew, RestVariableScope.GLOBAL, variableType);
    }

    protected RestVariable setBinaryVariable(MultipartHttpServletRequest request, String instanceId, int responseVariableType, boolean isNew) {
        return setBinaryVariable(request, instanceId, responseVariableType, isNew, RestVariableScope.GLOBAL);
    }
    
    protected RestVariable setBinaryVariable(MultipartHttpServletRequest request, String instanceId, int responseVariableType, boolean isNew,
            RestVariableScope scope) {

        // Validate input and set defaults
        if (request.getFileMap().size() == 0) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        // Get first file in the map, ignore possible other files
        MultipartFile file = request.getFile(request.getFileMap().keySet().iterator().next());

        if (file == null) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        String variableScope = null;
        String variableName = null;
        String variableType = null;

        Map<String, String[]> paramMap = request.getParameterMap();
        for (String parameterName : paramMap.keySet()) {

            if (paramMap.get(parameterName).length > 0) {

                if ("scope".equalsIgnoreCase(parameterName)) {
                    variableScope = paramMap.get(parameterName)[0];

                } else if ("name".equalsIgnoreCase(parameterName)) {
                    variableName = paramMap.get(parameterName)[0];

                } else if ("type".equalsIgnoreCase(parameterName)) {
                    variableType = paramMap.get(parameterName)[0];
                }
            }
        }

        try {

            // Validate input and set defaults
            if (variableName == null) {
                throw new FlowableIllegalArgumentException("No variable name was found in request body.");
            }

            if (variableType != null) {
                if (!CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variableType) && !CmmnRestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variableType)) {
                    throw new FlowableIllegalArgumentException("Only 'binary' and 'serializable' are supported as variable type.");
                }
            } else {
                variableType = CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE;
            }

            if (variableScope != null) {
                scope = RestVariable.getScopeFromString(variableScope);
            }

            if (variableType.equals(CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE)) {
                // Use raw bytes as variable value
                byte[] variableBytes = IOUtils.toByteArray(file.getInputStream());
                setVariable(instanceId, variableName, variableBytes, scope, isNew);

            } else if (isSerializableVariableAllowed) {
                // Try deserializing the object
                ObjectInputStream stream = new ObjectInputStream(file.getInputStream());
                Object value = stream.readObject();
                setVariable(instanceId, variableName, value, scope, isNew);
                stream.close();
            } else {
                throw new FlowableContentNotSupportedException("Serialized objects are not allowed");
            }


            return restResponseFactory.createBinaryRestVariable(variableName, scope, variableType, instanceId, responseVariableType);
        } catch (IOException ioe) {
            throw new FlowableIllegalArgumentException("Could not process multipart content", ioe);
        } catch (ClassNotFoundException ioe) {
            throw new FlowableContentNotSupportedException(
                    "The provided body contains a serialized object for which the class was not found: " + ioe.getMessage());
        }
    }

    protected void setVariable(String instanceId, String name, Object value, RestVariableScope scope, boolean isNew) {
        if (RestVariableScope.LOCAL == scope) {
            runtimeService.setLocalVariable(instanceId, name, value);
        } else {
            runtimeService.setVariable(instanceId, name, value);
        }
    }

    protected void setVariable(PlanItemInstance planItemInstance, String name, Object value, RestVariableScope scope, boolean isNew) {
        runtimeService.setVariable(planItemInstance.getCaseInstanceId(), name, value);
    }
}
