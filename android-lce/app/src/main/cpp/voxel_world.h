#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

struct VoxelVertex {
    float x, y, z, u, v, r, g, b;
};
class VoxelWorld {
public:
    static constexpr int SX=96, SY=40, SZ=96;
    float px=48.5f,py=21.0f,pz=48.5f,yaw=0.4f,pitch=-0.18f;
    uint8_t held=2;
    bool dirty=true;
    VoxelWorld();
    uint8_t get(int x,int y,int z) const;
    void set(int x,int y,int z,uint8_t id);
    int surfaceY(int x,int z) const;
    void generate(uint32_t seed=0x4A4C4345);
    void buildMesh(std::vector<VoxelVertex>& solid,std::vector<VoxelVertex>& translucent) const;
    void look(float deltaX,float deltaY);
    void setMove(float forward,float sideways);
    void jump();
    void tick(float dt);
    bool interact(bool place);
    void select(int slot);
    bool save(const std::string& path) const;
    bool load(const std::string& path);
    std::array<float,3> eye() const {return {px,py+1.62f,pz};}
private:
    std::vector<uint8_t> blocks;
    uint32_t seed=0;
    float velY=0,moveF=0,moveS=0;
    bool grounded=false;
    bool collides(float x,float y,float z) const;
    bool raycast(int& hitX,int& hitY,int& hitZ,int& emptyX,int& emptyY,int& emptyZ) const;
};
