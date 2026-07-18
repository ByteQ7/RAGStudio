#!/usr/bin/env python3
"""
离线逆地理编码 — 输入经纬度，返回省/市/区县信息。

依赖：python3 标准库（json, sys, os）
数据：同级目录下的 china_province/city/district.geojson

使用：python3 geo_reverse.py <纬度> <经度>

输出 JSON 格式：
  {"status": 1, "address": {"province": "...", "province_code": "...",
   "city": "...", "city_code": "...", "district": "...", "district_code": "..."}}
"""

import json
import os
import sys


def get_script_dir():
    """获取脚本所在目录（兼容各种调用方式）"""
    if hasattr(sys, "frozen"):  # PyInstaller
        return os.path.dirname(sys.executable)
    # __file__ 在正常运行时是脚本的路径
    script_path = os.path.abspath(__file__)
    return os.path.dirname(script_path)


def load_geojson(filename):
    """加载 GeoJSON 文件，返回 features 列表"""
    dir_path = get_script_dir()
    filepath = os.path.join(dir_path, filename)
    if not os.path.exists(filepath):
        # 兜底：尝试当前工作目录
        filepath = os.path.join(os.getcwd(), filename)
    if not os.path.exists(filepath):
        raise FileNotFoundError(f"数据文件不存在: {filepath}")
    with open(filepath, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("features", [])


def point_in_ring(point, ring):
    """射线法判断点是否在多边形环内"""
    x, y = point
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        xi, yi = ring[i]
        xj, yj = ring[j]
        # 检查射线是否穿过这条边
        if ((yi > y) != (yj > y)) and (
            x < (xj - xi) * (y - yi) / (yj - yi) + xi
        ):
            inside = not inside
        j = i
    return inside


def point_in_polygon(point, coordinates):
    """
    判断点是否在多边形或多边形集合内。
    GeoJSON coordinates 格式：
      Polygon:       [[[x,y], [x,y], ...]]
      MultiPolygon:  [[[[x,y], [x,y], ...]], [[[x,y], ...]]]
    """
    # MultiPolygon: 多个 Polygon 的数组
    if isinstance(coordinates[0][0][0], list):
        for polygon_coords in coordinates:
            if point_in_polygon(point, polygon_coords):
                return True
        return False
    # Polygon: 第一个环是外环，后续是内环（孔洞）
    # 先检查是否在外环内
    exterior = coordinates[0]
    if not point_in_ring(point, exterior):
        return False
    # 再检查是否在任何一个内环（孔洞）中
    for interior in coordinates[1:]:
        if point_in_ring(point, interior):
            return False
    return True


def find_region(features, point):
    """在 features 中查找包含 point 的第一个区域"""
    for feat in features:
        geom = feat.get("geometry", {})
        geom_type = geom.get("type")
        coords = geom.get("coordinates")
        if not coords:
            continue
        if geom_type == "Polygon":
            if point_in_polygon(point, coords):
                return feat.get("properties", feat)
        elif geom_type == "MultiPolygon":
            if point_in_polygon(point, coords):
                return feat.get("properties", feat)
    return None


def regeo(lat, lng, data_dir=None):
    """
    逆地理编码主函数
    
    参数：
        lat: 纬度
        lng: 经度
        data_dir: 数据文件目录（默认使用脚本所在目录）
    
    返回：
        dict: {"status": 1/0, "address": {...}}
    """
    point = (lng, lat)  # GeoJSON 使用 [经度, 纬度] 顺序
    
    try:
        # 确定数据文件路径
        if data_dir:
            # 如果指定了数据目录，拼接后传入 load_geojson
            province_file = os.path.join(data_dir, "china_province.geojson")
            city_file = os.path.join(data_dir, "china_city.geojson")
            district_file = os.path.join(data_dir, "china_district.geojson")
        else:
            # 不指定路径，load_geojson 自动使用脚本所在目录
            province_file = "china_province.geojson"
            city_file = "china_city.geojson"
            district_file = "china_district.geojson"
        
        # 加载数据（load_geojson 会自动解析到脚本目录）
        provinces = load_geojson(province_file)
        cities = load_geojson(city_file)
        districts = load_geojson(district_file)
        
        # 逐级查找
        province = find_region(provinces, point)
        if not province:
            return {
                "status": 0,
                "info": "坐标不在中国境内或超出数据范围",
                "address": {
                    "province": None, "province_code": None,
                    "city": None, "city_code": None,
                    "district": None, "district_code": None
                }
            }
        
        city = find_region(cities, point)
        district = find_region(districts, point)
        
        return {
            "status": 1,
            "info": "Successfully retrieved address.",
            "address": {
                "province": province.get("name"),
                "province_code": str(province.get("gb", "")),
                "city": city.get("name") if city else None,
                "city_code": str(city.get("gb", "")) if city else None,
                "district": district.get("name") if district else None,
                "district_code": str(district.get("gb", "")) if district else None
            }
        }
    except Exception as e:
        return {
            "status": 0,
            "info": f"处理出错: {str(e)}",
            "address": {
                "province": None, "province_code": None,
                "city": None, "city_code": None,
                "district": None, "district_code": None
            }
        }


def main():
    if len(sys.argv) < 3:
        print(json.dumps({
            "status": 0,
            "info": "参数不足。用法: python3 geo_reverse.py <纬度> <经度>",
            "address": {
                "province": None, "province_code": None,
                "city": None, "city_code": None,
                "district": None, "district_code": None
            }
        }, ensure_ascii=False))
        sys.exit(1)
    
    try:
        lat = float(sys.argv[1])
        lng = float(sys.argv[2])
    except ValueError:
        print(json.dumps({
            "status": 0,
            "info": "经纬度必须为数字",
            "address": {
                "province": None, "province_code": None,
                "city": None, "city_code": None,
                "district": None, "district_code": None
            }
        }, ensure_ascii=False))
        sys.exit(1)
    
    result = regeo(lat, lng)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
