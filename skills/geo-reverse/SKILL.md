# Geo Reverse SKILL — 经纬度逆地理编码

## 功能
将经纬度坐标转换为可读的地理位置信息，包括省份、城市、区县名称及行政编码。

## 数据来源
调用 OpenStreetMap Nominatim 逆向地理编码 API（免费，无需 API Key）。

## 使用场景
当 AI 收到用户发来的坐标信息（如 `我的位置：纬度 39.9042, 经度 116.4074`）时，使用此工具将坐标转换为城市名，以便进一步查询天气等信息。

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `lat` | string | 是 | 纬度，如 `39.9042` |
| `lng` | string | 是 | 经度，如 `116.4074` |

## 返回结果示例

调用 `geo_reverse` 并传入 `lat=39.9042, lng=116.4074` 返回：

```json
{
  "place_id": 417862833,
  "display_name": "台基厂头条14号院-10号院, 台基厂头条, ..., 东城区, 北京市, 100010, 中国",
  "address": {
    "city": "东城区",
    "country": "中国",
    "country_code": "cn"
  }
}
```

> 注意：对于北京市等直辖市，Nominatim 返回的 `address.city` 是区名（如"东城区"）。完整的城市信息在 `display_name` 中以"北京市"形式出现。建议优先从返回的 `display_name` 或 `address` 的组合中提取城市名。

## 使用示例（完整流程）

### 场景：用户查询天气

```
用户：我的位置：纬度 31.9242, 经度 120.4923

AI（ReACT 循环）：
  Thought: 用户提供了坐标，我需要将其转换为城市名
  Action: geo_reverse
  Action Input: {"lat": "31.9242", "lng": "120.4923"}
  → 返回：{"address": {"city": "苏州市", "province": "江苏省", ...}, "display_name": "...苏州市..."}

  Thought: 用户在苏州市。需要确认她查询的是本地还是其他城市。
  Action: FINISH
  Final Answer: 好的，您当前在江苏省苏州市。请问您想：
  [USER_CHOICE]
  查询苏州市的今天天气
  查询其他城市天气
  [/USER_CHOICE]
```

### 场景：北京市特殊处理

```
用户：我的位置：纬度 39.9042, 经度 116.4074

  Action: geo_reverse
  Action Input: {"lat": "39.9042", "lng": "116.4074"}
  → 返回：{"address": {"city": "东城区", ...}, "display_name": "...北京市..."}

  Thought: 从 display_name 中可以看到用户在"北京市"，address.city 中的"东城区"是区名
  Action: FINISH
  Final Answer: 您当前在北京市东城区。请问您想：
  [USER_CHOICE]
  查询北京市的今天天气
  查询其他城市天气
  [/USER_CHOICE]
```

## 注意事项
1. **仅限中国坐标**：此 API 全球通用，但主要使用场景是中国境内的坐标
2. **请求频率**：Nominatim 使用限制为每秒最多 1 次请求（本工具通常只需调用一次）
3. **北京市特殊结构**：北京、上海、天津、重庆这四个直辖市的 `address.city` 字段是区名而非市名，完整的城市信息在 `display_name` 中
4. **坐标格式**：纬度范围 -90~90，经度范围 -180~180。中国境内纬度约 18~54，经度约 73~135
