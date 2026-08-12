---
name: geo-reverse
description: 经纬度坐标逆地理编码。输入纬度和经度，返回对应的省份、城市、区县名称及行政编码。完全离线运行，使用本地中国行政区划边界数据。当你从用户消息中获取到经纬度坐标后，可以调用此工具将坐标转换为城市名。
---

# Geo Reverse SKILL — 经纬度逆地理编码

## 功能
将经纬度坐标转换为可读的地理位置信息，包括省份、城市、区县名称及行政编码。完全离线运行，使用本地中国行政区划边界数据，不依赖外部 API。

## 使用场景
当 AI 收到用户发来的坐标信息（如 `我的位置：纬度 39.9042, 经度 116.4074`）时，使用此工具将坐标转换为城市名，以便进一步查询天气等信息。

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `lat` | string | 是 | 纬度，如 `39.9042` |
| `lng` | string | 是 | 经度，如 `116.4074` |

## 返回结果

调用脚本 `python3 geo_reverse.py <纬度> <经度>` 返回 JSON：

```json
{
  "status": 1,
  "address": {
    "province": "北京市",
    "province_code": "110000",
    "city": "北京市",
    "city_code": "110000",
    "district": "东城区",
    "district_code": "110101"
  }
}
```

- `status`: 1=成功，0=未命中
- `province/city/district`: 省/市/区县名称
- `*_code`: 对应行政编码

## 使用示例（完整流程）

### 场景：用户查询天气

```
用户：我的位置：纬度 31.9242, 经度 120.4923

AI（ReACT 循环）：
  Thought: 用户提供了坐标，我需要将其转换为城市名
  Action: geo-reverse
  Action Input: {"lat": "31.9242", "lng": "120.4923"}
  → 返回：{"status": 1, "address": {"province": "江苏省", "city": "苏州市", "district": "吴中区", ...}}

  Thought: 用户在苏州市。需要确认她查询的是本地还是其他城市。
  Action: FINISH
  Final Answer: 好的，您当前在江苏省苏州市。请问您想：
  [USER_CHOICE]
  查询苏州市的今天天气
  查询其他城市天气
  [/USER_CHOICE]
```

### 场景：直辖市处理

```
用户：我的位置：纬度 39.9042, 经度 116.4074

  Action: geo-reverse
  Action Input: {"lat": "39.9042", "lng": "116.4074"}
  → 返回：{"status": 1, "address": {"province": "北京市", "city": "北京市", "district": "东城区", ...}}

  Thought: 用户在北京市东城区
  Action: FINISH
  Final Answer: 您当前在北京市东城区。请问您想：
  [USER_CHOICE]
  查询北京市的今天天气
  查询其他城市天气
  [/USER_CHOICE]
```

## 注意事项
1. **仅限中国坐标**：数据仅覆盖中国行政区划，国外坐标将返回 status=0
2. **坐标格式**：纬度范围 -90~90，经度范围 -180~180。中国境内纬度约 18~54，经度约 73~135
3. **直辖市**：北京、上海、天津、重庆的 province/city 均为直辖市名，区县信息在 district 字段
4. **离线运行**：数据文件随技能打包（scripts/china_*.geojson），无需网络
