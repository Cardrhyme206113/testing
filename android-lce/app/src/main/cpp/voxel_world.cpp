#include "voxel_world.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>

namespace {
constexpr uint8_t AIR=0, STONE=1, GRASS=2, DIRT=3, COBBLE=4, PLANKS=5, BEDROCK=7, WATER=9, SAND=12, LOG=17, LEAVES=18, BRICKS=45;
float clampf(float x,float a,float b){return std::max(a,std::min(b,x));}
uint32_t hash(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x*=0x846ca68bu; x^=x>>16;
    return x;
}
float noise(int x,int z,uint32_t seed) {
    return float(hash(uint32_t(x)*0x9e3779b9u ^ uint32_t(z)*0x85ebca6bu ^ seed) & 0xffffu)/65535.f;
}
float smoothNoise(float x,float z,uint32_t seed) {
    int xi=int(std::floor(x)),zi=int(std::floor(z));
    float fx=x-xi,fz=z-zi;
    fx=fx*fx*(3-2*fx);fz=fz*fz*(3-2*fz);
    float a=noise(xi,zi,seed),b=noise(xi+1,zi,seed);
    float c=noise(xi,zi+1,seed),d=noise(xi+1,zi+1,seed);
    return (a+(b-a)*fx)*(1-fz)+(c+(d-c)*fx)*fz;
}
int atlas(uint8_t id,int face) {
    // Classic 16x16 LCE terrain.png indices. Coordinates are 16 px tiles.
    switch(id) {
      case GRASS:return face==2?0:face==3?2:3;
      case STONE:return 1;
      case DIRT:return 2;
      case COBBLE:return 16;
      case PLANKS:return 4;
      case BEDROCK:return 17;
      case SAND:return 18;
      case LOG:return (face==2 || face==3)?21:20;
      case LEAVES:return 68;
      case WATER:return 14;
      case BRICKS:return 7;
      default:return 1;
    }
}
bool transparent(uint8_t id){return id==AIR || id==WATER || id==LEAVES;}
}
VoxelWorld::VoxelWorld():blocks(SX*SY*SZ,AIR){generate();}
uint8_t VoxelWorld::get(int x,int y,int z) const {
    if(y<0)return BEDROCK;
    if(x<0||x>=SX||z<0||z>=SZ||y>=SY)return AIR;
    return blocks[size_t((y*SZ+z)*SX+x)];
}
void VoxelWorld::set(int x,int y,int z,uint8_t id) {
    if(x<0||x>=SX||z<0||z>=SZ||y<=0||y>=SY)return;
    blocks[size_t((y*SZ+z)*SX+x)]=id;
    dirty=true;
}
int VoxelWorld::surfaceY(int x,int z) const {
    for(int y=SY-1;y>=0;--y) {
       const uint8_t id=get(x,y,z);
       if(id!=AIR && id!=WATER && id!=LEAVES && id!=LOG)return y;
    }
    return 0;
}
void VoxelWorld::generate(uint32_t s) {
    seed=s;
    std::fill(blocks.begin(),blocks.end(),AIR);
    for(int z=0;z<SZ;++z)for(int x=0;x<SX;++x) {
        const float h=12.0f+5.0f*(smoothNoise(x*.055f,z*.055f,s)-.5f)*2.0f
                   +2.0f*(smoothNoise(x*.13f,z*.13f,s+8)-.5f);
        const int maxY=std::max(5,std::min(SY-10,int(std::floor(h))));
        for(int y=0;y<=maxY;++y) {
            uint8_t id=y==0?BEDROCK:(y==maxY?(maxY<=11?SAND:GRASS):(y>=maxY-3?(maxY<=11?SAND:DIRT):STONE));
            blocks[size_t((y*SZ+z)*SX+x)]=id;
        }
        if(maxY<11)for(int y=maxY+1;y<=11;++y)blocks[size_t((y*SZ+z)*SX+x)]=WATER;
    }
    for(int z=5;z<SZ-5;++z)for(int x=5;x<SX-5;++x) {
        if(noise(x,z,s+129)>0.995f) {
            const int y=surfaceY(x,z);
            if(y<12||y>=SY-9||get(x,y,z)!=GRASS)continue;
            const int trunk=4+(hash(x*37u+z*113u+s)&1);
            for(int k=1;k<=trunk;++k)set(x,y+k,z,LOG);
            for(int oy=-2;oy<=2;++oy)for(int oz=-2;oz<=2;++oz)for(int ox=-2;ox<=2;++ox) {
                if(std::abs(ox)+std::abs(oz)+std::abs(oy)>4)continue;
                int tx=x+ox,tz=z+oz,ty=y+trunk+oy+1;
                if(get(tx,ty,tz)==AIR)set(tx,ty,tz,LEAVES);
            }
        }
    }
    px=48.5f;pz=48.5f;py=float(surfaceY(48,48)+1);
    // A tree canopy above the spawn is fine; don't bury the player in a trunk.
    for(int y=int(py);y<int(py)+3;++y)set(48,y,48,AIR);
    yaw=.65f;pitch=-.12f;velY=0;moveF=moveS=0;grounded=false;held=GRASS;dirty=true;
}
void VoxelWorld::buildMesh(std::vector<VoxelVertex>& opaque,std::vector<VoxelVertex>& water) const {
    opaque.clear();water.clear();
    opaque.reserve(600000);water.reserve(100000);
    // Face order: +X,-X,+Y,-Y,+Z,-Z. Re-use exactly the classic atlas.
    constexpr int neighbor[6][3]={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    constexpr float face[6][4][3]={
      {{1,0,0},{1,1,0},{1,1,1},{1,0,1}},
      {{0,0,1},{0,1,1},{0,1,0},{0,0,0}},
      {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
      {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},
      {{1,0,1},{1,1,1},{0,1,1},{0,0,1}},
      {{0,0,0},{0,1,0},{1,1,0},{1,0,0}}
    };
    constexpr float uv[4][2]={{0,1},{0,0},{1,0},{1,1}};
    constexpr int order[6]={0,1,2,0,2,3};
    for(int y=0;y<SY;++y)for(int z=0;z<SZ;++z)for(int x=0;x<SX;++x) {
        uint8_t id=get(x,y,z);if(id==AIR)continue;
        for(int d=0;d<6;++d){
            const uint8_t other=get(x+neighbor[d][0],y+neighbor[d][1],z+neighbor[d][2]);
            // Skip faces between two opaque blocks and same-class translucent blocks.
            if(id==WATER){if(other!=AIR && other!=LEAVES)continue;}
            else if(id==LEAVES){if(!transparent(other)||other==LEAVES)continue;}
            else if(!transparent(other))continue;
            std::vector<VoxelVertex>& target=id==WATER?water:opaque;
            int tile=atlas(id,d),tx=tile%16,ty=tile/16;
            const float eps=.40f/256.f;
            const float u0=tx/16.f+eps,u1=(tx+1)/16.f-eps;
            const float vTop=1.0f-ty/16.f-eps,vBottom=1.0f-(ty+1)/16.f+eps;
            const float shade=d==2?1.0f:d==3?.55f:(d<2?.78f:.86f);
            float r=shade,g=shade,b=shade;
            if(id==GRASS && d==2){r*=.58f;g*=.85f;b*=.37f;}
            if(id==LEAVES){r*=.63f;g*=.82f;b*=.48f;}
            if(id==WATER){r*=.70f;g*=.85f;b*=1.0f;}
            for(int k=0;k<6;++k){
                const int i=order[k];
                const float tu=uv[i][0]?u1:u0;
                const float tv=uv[i][1]?vBottom:vTop;
                target.push_back({x+face[d][i][0],y+face[d][i][1],z+face[d][i][2],tu,tv,r,g,b});
            }
        }
    }
}
void VoxelWorld::setMove(float forward,float sideways){moveF=clampf(forward,-1,1);moveS=clampf(sideways,-1,1);}
void VoxelWorld::look(float dx,float dy){
    yaw+=dx*.0042f;pitch=clampf(pitch-dy*.0042f,-1.48f,1.48f);
}
bool VoxelWorld::collides(float x,float y,float z) const {
    const float radius=.29f;
    for(int yy=int(std::floor(y));yy<=int(std::floor(y+1.79f));++yy)
      for(int zz=int(std::floor(z-radius));zz<=int(std::floor(z+radius));++zz)
        for(int xx=int(std::floor(x-radius));xx<=int(std::floor(x+radius));++xx) {
          uint8_t b=get(xx,yy,zz);
          if(b!=AIR && b!=WATER && b!=LEAVES)return true;
        }
    return false;
}
void VoxelWorld::jump(){if(grounded){velY=7.9f;grounded=false;}}
void VoxelWorld::tick(float dt) {
    dt=clampf(dt,0.f,.050f);
    const float len=std::sqrt(moveF*moveF+moveS*moveS),scale=len>1?1/len:1;
    const float dx=(std::sin(yaw)*moveF+std::cos(yaw)*moveS)*5.0f*scale*dt;
    const float dz=(std::cos(yaw)*moveF-std::sin(yaw)*moveS)*5.0f*scale*dt;
    if(!collides(px+dx,py,pz))px+=dx;
    if(!collides(px,py,pz+dz))pz+=dz;
    velY-=20.f*dt;
    float dy=velY*dt;
    const int steps=std::max(1,int(std::ceil(std::abs(dy)/.14f)));
    grounded=false;
    for(int i=0;i<steps;++i){
        if(collides(px,py+dy/steps,pz)){
            if(velY<0)grounded=true;
            velY=0;break;
        }
        py+=dy/steps;
    }
    // World edges are collision boundaries, not an invitation to fall forever.
    px=clampf(px,1.35f,SX-1.35f);pz=clampf(pz,1.35f,SZ-1.35f);
    if(py < 2) {py=float(surfaceY(int(px),int(pz))+1);velY=0;}
}
bool VoxelWorld::raycast(int& hx,int&hy,int&hz,int&ex,int&ey,int&ez) const {
    const float cp=std::cos(pitch),dx=std::sin(yaw)*cp,dy=std::sin(pitch),dz=std::cos(yaw)*cp;
    auto eyePos=eye();int oldX=int(std::floor(px)),oldY=int(std::floor(py+1.62f)),oldZ=int(std::floor(pz));
    for(float t=.15f;t<=6.5f;t+=.065f){
        int x=int(std::floor(eyePos[0]+dx*t)),y=int(std::floor(eyePos[1]+dy*t)),z=int(std::floor(eyePos[2]+dz*t));
        const uint8_t b=get(x,y,z);
        if(b!=AIR && b!=WATER && b!=LEAVES){
            hx=x;hy=y;hz=z;ex=oldX;ey=oldY;ez=oldZ;return true;
        }
        oldX=x;oldY=y;oldZ=z;
    }
    return false;
}
bool VoxelWorld::interact(bool place) {
    int x,y,z,ex,ey,ez;if(!raycast(x,y,z,ex,ey,ez))return false;
    if(place){
        if(ex<=0||ex>=SX-1||ez<=0||ez>=SZ-1||ey<=0||ey>=SY-1)return false;
        if(std::abs(float(ex)+.5f-px)<.82f &&
           std::abs(float(ez)+.5f-pz)<.82f &&
           float(ey)<py+1.8f&&float(ey)+1.f>py)return false;
        if(get(ex,ey,ez)!=AIR && get(ex,ey,ez)!=WATER)return false;
        set(ex,ey,ez,held);
    }else if(get(x,y,z)!=BEDROCK)set(x,y,z,AIR);
    return true;
}
void VoxelWorld::select(int slot) {
    constexpr uint8_t types[]={GRASS,DIRT,STONE,COBBLE,PLANKS,LOG,LEAVES,SAND,BRICKS};
    held=types[std::max(0,std::min(8,slot))];
}
bool VoxelWorld::save(const std::string& path) const {
    std::ofstream f(path,std::ios::binary|std::ios::trunc);if(!f)return false;
    const char magic[8]={'L','C','E','V','X','L','0','2'};
    f.write(magic,8);f.write(reinterpret_cast<const char*>(&seed),4);
    f.write(reinterpret_cast<const char*>(blocks.data()),std::streamsize(blocks.size()));
    f.write(reinterpret_cast<const char*>(&px),4);f.write(reinterpret_cast<const char*>(&py),4);
    f.write(reinterpret_cast<const char*>(&pz),4);f.write(reinterpret_cast<const char*>(&yaw),4);
    f.write(reinterpret_cast<const char*>(&pitch),4);
    return bool(f);
}
bool VoxelWorld::load(const std::string& path) {
    std::ifstream f(path,std::ios::binary);if(!f)return false;
    char magic[8];if(!f.read(magic,8) || std::memcmp(magic,"LCEVXL02",8)!=0)return false;
    uint32_t newSeed;if(!f.read(reinterpret_cast<char*>(&newSeed),4))return false;
    std::vector<uint8_t> data(blocks.size());
    if(!f.read(reinterpret_cast<char*>(data.data()),std::streamsize(data.size())))return false;
    float coords[5];
    if(!f.read(reinterpret_cast<char*>(coords),sizeof(coords)))return false;
    if(!(coords[0]>0&&coords[0]<SX&&coords[1]>0&&coords[1]<SY&&coords[2]>0&&coords[2]<SZ))return false;
    blocks.swap(data);seed=newSeed;
    px=coords[0];py=coords[1];pz=coords[2];yaw=coords[3];pitch=clampf(coords[4],-1.48f,1.48f);
    velY=0;grounded=false;dirty=true;return true;
}
