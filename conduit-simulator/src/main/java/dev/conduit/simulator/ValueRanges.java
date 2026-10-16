package dev.conduit.simulator;

/** 数值范围（纯函数）：漂移类测点的合理范围表，以及无上下限的其他测点按单位推断的范围。 */
final class ValueRanges {

    private ValueRanges() {
    }

    /**
     * 漂移类测点（水压、pH、浊度、余氯、湿度、PM2.5、CO₂、风速）的合理范围。
     *
     * @return {下限, 上限}；不属于漂移类时为 null
     */
    static double[] drift(PropertyDef p) {
        String unit = PointClassifier.normalizeUnit(p.unit());
        String text = PointClassifier.text(p).replace("₂", "2");
        String name = p.name() == null ? "" : p.name();
        if (PointClassifier.containsAny(text, "水压", "压力", "pressure")) {
            return switch (unit) {
                case "kpa" -> new double[]{250, 450};
                case "bar" -> new double[]{2.5, 4.5};
                default -> new double[]{0.25, 0.45};
            };
        }
        if (p.identifier().equalsIgnoreCase("ph") || name.contains("pH") || text.contains("酸碱")) {
            return new double[]{6.8, 7.8};
        }
        if (PointClassifier.containsAny(text, "浊度", "turbidity") || unit.equals("ntu")) {
            return new double[]{0.1, 1.0};
        }
        if (PointClassifier.containsAny(text, "余氯", "chlorine")) {
            return new double[]{0.3, 0.8};
        }
        if (PointClassifier.containsAny(text, "湿度", "humidity") || unit.equals("%rh")) {
            return new double[]{40, 75};
        }
        if (PointClassifier.containsAny(text, "pm2.5", "pm25")) {
            return new double[]{10, 75};
        }
        if (PointClassifier.containsAny(text, "co2", "二氧化碳") || unit.equals("ppm")) {
            return new double[]{420, 900};
        }
        if (PointClassifier.containsAny(text, "风速", "wind")) {
            return new double[]{1, 8};
        }
        return null;
    }

    /**
     * 其他数值测点的游走范围：上下限都有时就是 [min, max]；否则按单位推断（再无则 0～100），
     * 再用已有的一侧上限或下限替换推断值。
     */
    static double[] infer(PropertyDef p) {
        Double min = p.minValue();
        Double max = p.maxValue();
        if (min != null && max != null) {
            return new double[]{min, max};
        }
        double[] byUnit = switch (PointClassifier.normalizeUnit(p.unit())) {
            case "hz" -> new double[]{49.8, 50.2};
            case "%" -> new double[]{0, 100};
            case "kvar" -> new double[]{0, 50};
            case "kva" -> new double[]{0, 100};
            case "v" -> new double[]{200, 240};
            case "a" -> new double[]{0, 50};
            case "kw" -> new double[]{0, 100};
            case "℃" -> new double[]{0, 40};
            case "mpa" -> new double[]{0, 1};
            case "m/s" -> new double[]{0, 10};
            default -> new double[]{0, 100};
        };
        double span = byUnit[1] - byUnit[0];
        double lo = byUnit[0];
        double hi = byUnit[1];
        if (min != null) {
            lo = min;
            if (hi <= lo) {
                hi = lo + span;
            }
        }
        if (max != null) {
            hi = max;
            if (lo >= hi) {
                lo = hi - span;
            }
        }
        return new double[]{lo, hi};
    }
}
