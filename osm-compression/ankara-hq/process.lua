-- Ankara HQ: preserve all buildings, roads/paths, addresses, POIs and names.
-- Primary OSM names plus common alternate/localized name fields are copied.

node_keys = {
  "name", "place", "amenity", "shop", "tourism", "historic", "leisure",
  "office", "craft", "healthcare", "emergency", "railway", "public_transport",
  "natural", "man_made", "aeroway", "addr:housenumber"
}

way_keys = {
  "name", "highway", "railway", "waterway", "natural", "water", "landuse",
  "leisure", "boundary", "building", "place", "amenity", "shop", "office",
  "craft", "healthcare", "tourism", "historic", "man_made", "aeroway",
  "addr:housenumber"
}

local NAME_KEYS = {
  {"name","name"}, {"name:tr","name:tr"}, {"name:en","name:en"},
  {"official_name","official_name"}, {"alt_name","alt_name"},
  {"short_name","short_name"}, {"loc_name","loc_name"}, {"old_name","old_name"},
  {"brand","brand"}, {"operator","operator"}, {"ref","ref"}
}

local ADDR_KEYS = {
  {"addr:housenumber","housenumber"}, {"addr:street","street"},
  {"addr:place","place"}, {"addr:city","city"}, {"addr:district","district"},
  {"addr:postcode","postcode"}, {"addr:unit","unit"}
}

local function copy_names()
  for _,kv in ipairs(NAME_KEYS) do
    local v = Find(kv[1])
    if v ~= "" then Attribute(kv[2], v) end
  end
end

local function copy_addr()
  for _,kv in ipairs(ADDR_KEYS) do
    local v = Find(kv[1])
    if v ~= "" then Attribute(kv[2], v) end
  end
end

local function attr_if(k, out)
  local v = Find(k)
  if v ~= "" then Attribute(out or k, v) end
end

local function road_class(h)
  if h == "motorway" or h == "motorway_link" then return "motorway", 5 end
  if h == "trunk" or h == "trunk_link" then return "trunk", 6 end
  if h == "primary" or h == "primary_link" then return "primary", 7 end
  if h == "secondary" or h == "secondary_link" then return "secondary", 8 end
  if h == "tertiary" or h == "tertiary_link" then return "tertiary", 9 end
  if h == "residential" or h == "unclassified" or h == "living_street" then return "minor", 10 end
  if h == "service" then return "service", 12 end
  if h == "track" or h == "path" or h == "footway" or h == "cycleway" or h == "bridleway" or h == "steps" or h == "pedestrian" then return "path", 12 end
  return "other", 11
end

local function poi_class()
  local keys = {"amenity","shop","tourism","historic","leisure","office","craft","healthcare","emergency","aeroway","man_made","natural"}
  for _,k in ipairs(keys) do
    local v = Find(k)
    if v ~= "" then return k, v end
  end
  return "", ""
end

local function write_address_point()
  if Find("addr:housenumber") == "" then return false end
  Layer("address")
  copy_addr()
  copy_names()
  MinZoom(12)
  return true
end

function node_function(node)
  local handled = false
  local place = Find("place")
  if place ~= "" then
    Layer("place")
    Attribute("class", place)
    copy_names(); copy_addr()
    if place == "city" then MinZoom(3)
    elseif place == "town" then MinZoom(6)
    elseif place == "village" then MinZoom(8)
    else MinZoom(10) end
    handled = true
  end

  local pk, pv = poi_class()
  if pv ~= "" then
    Layer("poi")
    Attribute("class", pv); Attribute("class_key", pk)
    copy_names(); copy_addr()
    MinZoom(10)
    handled = true
  end

  if write_address_point() then handled = true end

  if Find("name") ~= "" and not handled then
    Layer("named")
    Attribute("class", "named_node")
    copy_names(); copy_addr()
    MinZoom(12)
  end
end

function way_function()
  local handled = false
  local highway = Find("highway")
  if highway ~= "" then
    local cls, z = road_class(highway)
    Layer("road", false)
    Attribute("class", cls); Attribute("highway", highway)
    copy_names(); copy_addr()
    attr_if("surface"); attr_if("access"); attr_if("oneway"); attr_if("bridge"); attr_if("tunnel"); attr_if("lanes"); attr_if("maxspeed")
    MinZoom(z)
    handled = true
  end

  local railway = Find("railway")
  if railway ~= "" then
    Layer("rail", false)
    Attribute("class", railway)
    copy_names(); attr_if("service"); attr_if("electrified"); attr_if("gauge")
    MinZoom(railway == "rail" and 7 or 10)
    handled = true
  end

  local waterway = Find("waterway")
  if waterway ~= "" then
    Layer("waterway", false)
    Attribute("class", waterway); copy_names()
    MinZoom(waterway == "river" and 7 or 10)
    handled = true
  end

  local natural = Find("natural")
  local water = Find("water")
  if natural == "water" or water ~= "" then
    Layer("water", true)
    Attribute("class", water ~= "" and water or "water")
    copy_names(); attr_if("intermittent")
    MinZoom(6)
    handled = true
  end

  local boundary = Find("boundary")
  if boundary == "administrative" then
    local level = Find("admin_level")
    Layer("boundary", false)
    Attribute("level", level); copy_names()
    if level == "2" then MinZoom(3)
    elseif level == "4" then MinZoom(4)
    elseif level == "6" then MinZoom(6)
    else MinZoom(8) end
    handled = true
  end

  local lu = Find("landuse")
  local leisure = Find("leisure")
  if lu ~= "" or (natural ~= "" and natural ~= "water" and natural ~= "coastline") or leisure ~= "" then
    Layer("landuse", true)
    if lu ~= "" then Attribute("class", lu); Attribute("class_key", "landuse")
    elseif leisure ~= "" then Attribute("class", leisure); Attribute("class_key", "leisure")
    else Attribute("class", natural); Attribute("class_key", "natural") end
    copy_names()
    MinZoom(9)
    handled = true
  end

  local hn = Find("addr:housenumber")
  if hn ~= "" then
    LayerAsCentroid("address")
    copy_addr(); copy_names()
    MinZoom(12)
  end

  local building = Find("building")
  if building ~= "" then
    Layer("building", true)
    Attribute("class", building)
    copy_names(); copy_addr()
    attr_if("building:levels", "levels"); attr_if("height"); attr_if("min_height"); attr_if("roof:shape", "roof_shape"); attr_if("amenity"); attr_if("shop"); attr_if("office")
    MinZoom(13)
    handled = true
  end

  local pk, pv = poi_class()
  if pv ~= "" then
    LayerAsCentroid("poi")
    Attribute("class", pv); Attribute("class_key", pk)
    copy_names(); copy_addr()
    MinZoom(10)
    handled = true
  end

  local place = Find("place")
  if place ~= "" then
    LayerAsCentroid("place")
    Attribute("class", place); copy_names(); copy_addr()
    MinZoom(8)
    handled = true
  end

  if Find("name") ~= "" and not handled then
    LayerAsCentroid("named")
    Attribute("class", "named_feature")
    copy_names(); copy_addr()
    MinZoom(12)
  end
end
