package org.ihtsdo.rvf.controller;

import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.helper.JsonEntityGenerator;
import org.ihtsdo.rvf.service.AssertionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/assertions")
public class AssertionController {

    @RequestMapping
    @ResponseBody
    public List<Map<String, Object>> getAssertions(HttpServletRequest request) {
        List<Assertion> assertions = new ArrayList<>();//assertionService.findAll();
        // temporary
        assertions.add(new Assertion(1L, "First"));
        assertions.add(new Assertion(2L, "Second"));
        return entityGenerator.getEntityCollection(assertions, request);
    }

    @RequestMapping("/{id}")
    @ResponseBody
    public Map<String, Object> getAssertion(@PathVariable Long id) {
        Assertion assertion = new Assertion(id, "some label"); //assertionService.find(id);
        return entityGenerator.getEntity(assertion);
    }

    @RequestMapping(value = "", method = RequestMethod.POST, consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map> createExtension(@RequestBody(required = false) Map<String, String> json) {
        String name = json.get("name");
        Assertion assertion = assertionService.create(name);

        Map<String, Object> entityHypermedia = entityGenerator.getEntity(assertion);
        return new ResponseEntity<Map>(entityHypermedia, HttpStatus.CREATED);
    }

    @Autowired
    private AssertionService assertionService;

    @Autowired
    private JsonEntityGenerator entityGenerator;
}
