package dev.conduit.server.thingmodel;

import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;
import dev.conduit.server.thingmodel.ThingModelDtos.PropertyView;
import dev.conduit.server.thingmodel.ThingModelDtos.ThingModelDocument;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products/{productKey}")
public class ThingModelController {

    private final ThingModelService service;

    public ThingModelController(ThingModelService service) {
        this.service = service;
    }

    @GetMapping("/properties")
    public List<PropertyView> list(@PathVariable String productKey) {
        return service.list(productKey);
    }

    @PostMapping("/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyView create(@PathVariable String productKey, @RequestBody PropertyRequest request) {
        return service.create(productKey, request);
    }

    @PutMapping("/properties/{identifier}")
    public PropertyView update(@PathVariable String productKey, @PathVariable String identifier,
                               @RequestBody PropertyRequest request) {
        return service.update(productKey, identifier, request);
    }

    @DeleteMapping("/properties/{identifier}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String productKey, @PathVariable String identifier) {
        service.delete(productKey, identifier);
    }

    @GetMapping("/thing-model")
    public ThingModelDocument export(@PathVariable String productKey) {
        return service.export(productKey);
    }

    @PutMapping("/thing-model")
    public List<PropertyView> importModel(@PathVariable String productKey,
                                          @RequestParam(defaultValue = "merge") String mode,
                                          @RequestBody ThingModelDocument document) {
        return service.importModel(productKey, document, ImportMode.parse(mode));
    }
}
