-- Purpose-built compact OSM -> vector tile schema.
-- The workflow replaces __PROFILE__ and __BUILDING_MODE__ before running tilemaker.
PROFILE = "__PROFILE__"
BUILDING_MODE = "__BUILDING_MODE__"

node_keys = {
  "place", "amenity", "shop", "tourism", "historic", "leisure",
  "railway", "public_transport", "addr:housenumber"
}

way_keys = {
  "highway", "railway", "waterway", "natural", "water", "landuse",
  "leisure", "boundary", "building", "place", "amenity", "shop",
  "tourism", "historic", "addr:housenumber"
}

local function put_name()
  local n = Find("name")
  if n ~= "" then Attribute("name", n) end
end

local function road_class(h)
  if h == "motorway" or h == "motorway_link" then return "motorway", 5 end
  if h == "trunk" or h == "trunk_link" then return "trunk", 6 end
  if h == "primary" or h == "primary_link" then return "primary", 7 end
  if h == "secondary" or h == "secondary_link" then return "secondary", 8 end
  if h == "tertiary" or h == "tertiary_link" then return "tertiary", 9 end
  if h == "residential" or h == "unclassified" or h == "living_street" then return "minor", 10 end
  if h == "service" then return "service", 12 end
  if h == "track" or h == "path" or h == "footway" or h == "cycleway" or h == "bridleway" or h == "steps" then return "path", 12 end
  return "other", 11
end

local function poi_ok()
  local n = Find("name")
  local a = Find("amenity")
  local s = Find("shop")
  local t = Find("tourism")
  local h = Find("historic")
  if PROFILE ~= "compact" then return (a ~= "" or s ~= "" or t ~= "" or h ~= "") end
  if n ~= "" then return true end
  return a == "hospital" or a == "clinic" or a == "police" or a == "fire_station" or
         a == "fuel" or a == "bus_station" or a == "ferry_terminal" or a == "university"
end

local function write_address_point()
  local hn = Find("addr:housenumber")
  if hn == "" then return end
  Layer("address")
  Attribute("hn", hn)
  local street = Find("addr:street")
  if street ~= "" then Attribute("street", street) end
  MinZoom(12)
end

function node_function(node)
  local place = Find("place")
  if place ~= "" then
    Layer("place")
    Attribute("class", place)
    put_name()
    if place == "city" then MinZoom(3)
    elseif place == "town" then MinZoom(6)
    elseif place == "village" then MinZoom(8)
    else MinZoom(10) end
  end

  if poi_ok() then
    Layer("poi")
    local cls = Find("amenity")
    if cls == "" then cls = Find("shop") end
    if cls == "" then cls = Find("tourism") end
    if cls == "" then cls = Find("historic") end
    Attribute("class", cls)
    put_name()
    MinZoom(PROFILE == "compact" and 12 or 11)
  end

  write_address_point()
end

function way_function()
  local highway = Find("highway")
  if highway ~= "" then
    local cls, z = road_class(highway)
    if not (PROFILE == "compact" and cls == "path") then
      Layer("road", false)
      Attribute("class", cls)
      put_name()
      MinZoom(z)
    end
  end

  local railway = Find("railway")
  if railway == "rail" or railway == "light_rail" or railway == "subway" or railway == "tram" then
    Layer("rail", false)
    Attribute("class", railway)
    put_name()
    if railway == "rail" then MinZoom(7) else MinZoom(10) end
  end

  local waterway = Find("waterway")
  if waterway == "river" or waterway == "canal" or waterway == "stream" then
    Layer("waterway", false)
    Attribute("class", waterway)
    put_name()
    if waterway == "river" then MinZoom(7) elseif waterway == "canal" then MinZoom(9) else MinZoom(12) end
  end

  local natural = Find("natural")
  local water = Find("water")
  if natural == "coastline" then
    Layer("coastline", false)
    MinZoom(4)
  elseif natural == "water" or water == "reservoir" then
    Layer("water", true)
    local cls = water
    if cls == "" then cls = "water" end
    Attribute("class", cls)
    put_name()
    MinZoom(6)
  end

  local boundary = Find("boundary")
  if boundary == "administrative" then
    local level = Find("admin_level")
    Layer("boundary", false)
    if level ~= "" then Attribute("level", level) end
    put_name()
    local nz = 8
    if level == "2" then nz = 3
    elseif level == "4" then nz = 5
    elseif level == "6" then nz = 7 end
    MinZoom(nz)
  end

  local lu = Find("landuse")
  local leisure = Find("leisure")
  if lu ~= "" or leisure == "park" or leisure == "nature_reserve" then
    local cls = lu
    if cls == "" then cls = leisure end
    local keep = true
    if PROFILE == "compact" then
      keep = (cls == "forest" or cls == "residential" or cls == "industrial" or cls == "commercial" or cls == "park" or cls == "nature_reserve")
    end
    if keep then
      Layer("landuse", true)
      Attribute("class", cls)
      put_name()
      MinZoom(PROFILE == "compact" and 11 or 10)
    end
  end

  -- Preserve address data as a point even when building polygons are discarded.
  local hn = Find("addr:housenumber")
  if hn ~= "" then
    LayerAsCentroid("address")
    Attribute("hn", hn)
    local street = Find("addr:street")
    if street ~= "" then Attribute("street", street) end
    MinZoom(12)
  end

  local building = Find("building")
  if building ~= "" and BUILDING_MODE ~= "none" then
    local keep = BUILDING_MODE == "all"
    if BUILDING_MODE == "named" then
      keep = (Find("name") ~= "" or hn ~= "")
    end
    if keep then
      Layer("building", true)
      put_name()
      MinZoom(13)
    end
  end

  if poi_ok() then
    LayerAsCentroid("poi")
    local cls = Find("amenity")
    if cls == "" then cls = Find("shop") end
    if cls == "" then cls = Find("tourism") end
    if cls == "" then cls = Find("historic") end
    Attribute("class", cls)
    put_name()
    MinZoom(PROFILE == "compact" and 12 or 11)
  end

  local place = Find("place")
  if place ~= "" then
    LayerAsCentroid("place")
    Attribute("class", place)
    put_name()
    MinZoom(8)
  end
end
