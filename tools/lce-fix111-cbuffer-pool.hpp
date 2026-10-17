#pragma once
#include <algorithm>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

// Reusable, thread-safe immutable render-command snapshots.
class LceCommandBufferPool {
public:
    using Command = std::function<void()>;
    using List = std::vector<Command>;
    using Snapshot = std::shared_ptr<const List>;
    int create(int count) {
        std::lock_guard<std::mutex> lock(mutex_);
        int n=std::max(0,count);
        if(!n) return static_cast<int>(lists_.size());
        for(auto it=free_.begin();it!=free_.end();++it) {
            if(it->second<n) continue;
            int first=it->first, left=it->second-n;
            free_.erase(it);
            if(left) free_.emplace(first+n,left);
            return first;
        }
        int first=static_cast<int>(lists_.size());
        lists_.resize(static_cast<size_t>(first)+static_cast<size_t>(n));
        return first;
    }
    void release(int first,int count) {
        if(first<0||count<=0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        int size=static_cast<int>(lists_.size());
        if(first>=size) return;
        int end=first+std::min(count,size-first);
        for(int i=first;i<end;++i) lists_[static_cast<size_t>(i)].reset();
        int mergedFirst=first,mergedEnd=end;
        auto it=free_.lower_bound(first);
        if(it!=free_.begin()) {
            auto prev=std::prev(it);
            if(prev->first+prev->second>=mergedFirst) {
                mergedFirst=prev->first;
                mergedEnd=std::max(mergedEnd,prev->first+prev->second);
                it=free_.erase(prev);
            }
        }
        while(it!=free_.end()&&it->first<=mergedEnd) {
            mergedEnd=std::max(mergedEnd,it->first+it->second);
            it=free_.erase(it);
        }
        free_.emplace(mergedFirst,mergedEnd-mergedFirst);
        auto last=free_.empty()?free_.end():std::prev(free_.end());
        if(last!=free_.end()&&last->first+last->second==size) {
            lists_.resize(static_cast<size_t>(last->first));
            free_.erase(last);
        }
    }
    void publish(int id,List commands) {
        if(id<0) return;
        Snapshot snapshot=std::make_shared<const List>(std::move(commands));
        std::lock_guard<std::mutex> lock(mutex_);
        if(static_cast<size_t>(id)>=lists_.size()) lists_.resize(static_cast<size_t>(id)+1);
        lists_[static_cast<size_t>(id)]=std::move(snapshot);
    }
    Snapshot get(int id) const {
        if(id<0) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        return static_cast<size_t>(id)<lists_.size()?lists_[static_cast<size_t>(id)]:Snapshot{};
    }
    void clear(int id) {
        if(id<0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if(static_cast<size_t>(id)<lists_.size()) lists_[static_cast<size_t>(id)].reset();
    }
    int size(int id) const {
        auto snapshot=get(id);
        return snapshot?static_cast<int>(snapshot->size()):0;
    }
    size_t slots() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return lists_.size();
    }
private:
    mutable std::mutex mutex_;
    std::vector<Snapshot> lists_;
    std::map<int,int> free_;
};
