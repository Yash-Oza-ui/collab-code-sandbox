package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Request DTO for {@code POST /internal/runs} (T-18 Decision 7, §18;
 * {@code docs/schemas/internal-run-request.json}).
 *
 * Used by ai-ml-service to request container executions from execution-service
 * for purposes {@code measurement}, {@code test_batch}, or {@code sanitizer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternalRunRequest {

    public static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private String request_id;
    private String room_id;
    private String purpose;
    private String language;
    private String code;
    private String stdin;
    private List<String> inputs;
    private Integer repeats;
    private String reference_solution;
    private List<String> expected_outputs;
    private List<String> sanitizers;

    public String getRequest_id()                 { return request_id; }
    public void setRequest_id(String request_id)  { this.request_id = request_id; }

    public String getRoom_id()                    { return room_id; }
    public void setRoom_id(String room_id)        { this.room_id = room_id; }

    public String getPurpose()                    { return purpose; }
    public void setPurpose(String purpose)        { this.purpose = purpose; }

    public String getLanguage()                   { return language; }
    public void setLanguage(String language)      { this.language = language; }

    public String getCode()                       { return code; }
    public void setCode(String code)              { this.code = code; }

    public String getStdin()                      { return stdin; }
    public void setStdin(String stdin)            { this.stdin = stdin; }

    public List<String> getInputs()               { return inputs; }
    public void setInputs(List<String> inputs)    { this.inputs = inputs; }

    public Integer getRepeats()                   { return repeats; }
    public void setRepeats(Integer repeats)       { this.repeats = repeats; }

    public String getReference_solution()                     { return reference_solution; }
    public void setReference_solution(String reference_solution) { this.reference_solution = reference_solution; }

    public List<String> getExpected_outputs()                 { return expected_outputs; }
    public void setExpected_outputs(List<String> expected_outputs) { this.expected_outputs = expected_outputs; }

    public List<String> getSanitizers()                       { return sanitizers; }
    public void setSanitizers(List<String> sanitizers)        { this.sanitizers = sanitizers; }
}
