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

/**
 * 物模型 REST 接口（/api/v1/products/{productKey}）：属性的增删改查，以及整份物模型的导出与导入。给 UI 的产品详情页使用。
 */
@RestController
@RequestMapping("/api/v1/products/{productKey}")
public class ThingModelController {

    private final ThingModelService service;

    public ThingModelController(ThingModelService service) {
        this.service = service;
    }

    /**
     * 列出产品的全部属性（按创建顺序）。
     */
    @GetMapping("/properties")
    public List<PropertyView> list(@PathVariable String productKey) {
        return service.list(productKey);
    }

    /**
     * 新增属性，成功返回 201。
     */
    @PostMapping("/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyView create(@PathVariable String productKey, @RequestBody PropertyRequest request) {
        return service.create(productKey, request);
    }

    /**
     * 修改属性；路径中的 identifier 优先，数据类型不可变。
     */
    @PutMapping("/properties/{identifier}")
    public PropertyView update(@PathVariable String productKey, @PathVariable String identifier,
                               @RequestBody PropertyRequest request) {
        return service.update(productKey, identifier, request);
    }

    /**
     * 删除属性，成功返回 204。已写入 InfluxDB 的历史数据不受影响。
     */
    @DeleteMapping("/properties/{identifier}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String productKey, @PathVariable String identifier) {
        service.delete(productKey, identifier);
    }

    /**
     * 导出整份物模型（JSON，格式与导入一致）。
     */
    @GetMapping("/thing-model")
    public ThingModelDocument export(@PathVariable String productKey) {
        return service.export(productKey);
    }

    /**
     * 导入物模型。mode 为 merge（默认，新增/更新、保留其他）或 replace（以文件为准，删除文件中没有的属性）；
     * 任一属性校验失败则整体不生效。
     */
    @PutMapping("/thing-model")
    public List<PropertyView> importModel(@PathVariable String productKey,
                                          @RequestParam(defaultValue = "merge") String mode,
                                          @RequestBody ThingModelDocument document) {
        return service.importModel(productKey, document, ImportMode.parse(mode));
    }
}
