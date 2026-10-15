package dev.conduit.server.thingmodel;

import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;
import dev.conduit.server.web.ItemErrorsException.ItemError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 导入前校验整个文件，收集全部错误；任一错误则整体不生效。 */
final class ThingModelImport {

    private ThingModelImport() {
    }

    static List<ItemError> validate(List<PropertyRequest> items, Map<String, DataType> existingTypes, ImportMode mode) {
        List<ItemError> errors = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            PropertyRequest item = items.get(i);
            if (item == null) {
                errors.add(new ItemError(null, "第 " + (i + 1) + " 项为空"));
                continue;
            }
            String id = item.identifier();
            PropertyRules.check(item).forEach(message -> errors.add(new ItemError(id, message)));
            if (id != null && !identifiers.add(id)) {
                errors.add(new ItemError(id, "标识重复"));
            }
            DataType requested = PropertyRules.parseType(item.dataType());
            DataType current = id == null ? null : existingTypes.get(id);
            if (current != null && requested != null && current != requested) {
                errors.add(new ItemError(id, "数据类型不可修改（现为 " + current + "）"));
            }
        }
        int total;
        if (mode == ImportMode.REPLACE) {
            total = identifiers.size();
        } else {
            Set<String> union = new HashSet<>(existingTypes.keySet());
            union.addAll(identifiers);
            total = union.size();
        }
        if (total > PropertyRules.MAX_PROPERTIES) {
            errors.add(new ItemError(null, "导入后属性数为 " + total + "，超过上限 " + PropertyRules.MAX_PROPERTIES));
        }
        return errors;
    }
}
