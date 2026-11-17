#include "vanilla_pass_scheduler.h"
#include <cstdio>
#include <cstdlib>

using namespace d3d12_mc::vanilla::scheduler;

static void require(bool condition, const char* message) {
    if (!condition) {
        std::fprintf(stderr, "%s\n", message);
        std::exit(1);
    }
}

static ResourceUse use(void* resource, uint32_t subresource, Access access, uint32_t state) {
    return {resource, 1, subresource, 1, 4, access, state, state};
}

int main() {
    int storage = 0;
    PassResourceScheduler scheduler;
    auto write = use(&storage, 1, Access::Write, 0x4);
    auto first = scheduler.prepare(1, &write, 1);
    require(first.ok && scheduler.commit(first), "initial write failed");
    auto read = use(&storage, 1, Access::Read, 0xC0);
    for (uint32_t pass = 2; pass < 100002; ++pass) {
        auto result = scheduler.prepare(pass, &read, 1);
        require(result.ok && result.dependencies.size() == 1 &&
            result.dependencies[0].priorPass == 1 && result.dependencies[0].readAfterWrite,
            "consecutive reads lost writer or accumulated history");
        require(scheduler.commit(result), "read commit failed");
    }
    auto disjoint = use(&storage, 2, Access::Write, 0x4);
    auto independent = scheduler.prepare(100002, &disjoint, 1);
    require(independent.ok && independent.dependencies.empty() && scheduler.commit(independent),
        "disjoint subresource gained dependency");
    auto overwrite = scheduler.prepare(100003, &write, 1);
    require(overwrite.ok && overwrite.dependencies.size() == 2,
        "write did not retain writer/latest reader frontier");
    bool war = false;
    bool waw = false;
    for (const auto& dependency : overwrite.dependencies) {
        war |= dependency.priorPass == 100001 && dependency.writeAfterRead;
        waw |= dependency.priorPass == 1 && dependency.writeAfterWrite;
    }
    require(war && waw && scheduler.commit(overwrite), "ordered WAR/WAW frontier failed");
    require(!scheduler.commit(overwrite), "stale plan committed");
    auto changed = read;
    changed.version = 2;
    require(!scheduler.prepare(100004, &changed, 1).ok, "changed incarnation accepted");
    scheduler.reset();
    auto uav = use(&storage, 0, Access::Uav, kUavState);
    uav.externalState = ExternalState::Known;
    uav.postExternalState = 0xC0;
    auto external = scheduler.prepare(1, &uav, 1);
    require(external.ok && scheduler.commit(external), "external exit commit failed");
    uav.externalState = ExternalState::Unchanged;
    auto noBarrier = scheduler.prepare(2, &uav, 1);
    require(noBarrier.ok && noBarrier.barriers.empty() && scheduler.commit(noBarrier),
        "external exit state lost");
    auto barrier = scheduler.prepare(3, &uav, 1);
    require(barrier.ok && barrier.barriers.size() == 1 && barrier.barriers[0].uav,
        "UAV ordering lost");
    scheduler.fail();
    require(!scheduler.commit(barrier) && !scheduler.prepare(3, &read, 1).ok,
        "poisoned recording accepted work");
    scheduler.reset();
    auto whole = use(&storage, UINT32_MAX, Access::Write, 0x4);
    whole.subresourceCount = 4;
    auto wide = scheduler.prepare(1, &whole, 1);
    require(wide.ok && scheduler.commit(wide), "whole range write failed");
    auto middle = scheduler.prepare(2, &write, 1);
    require(middle.ok && scheduler.commit(middle), "split write failed");
    auto other = use(&storage, 2, Access::Read, 0xC0);
    auto outside = scheduler.prepare(3, &other, 1);
    require(outside.ok && outside.dependencies.size() == 1 &&
        outside.dependencies[0].priorPass == 1, "partial overwrite erased untouched range");
    auto inside = scheduler.prepare(3, &read, 1);
    require(inside.ok && inside.dependencies.size() == 1 &&
        inside.dependencies[0].priorPass == 2, "partial overwrite retained obsolete writer");
    scheduler.reset();
    require(!scheduler.commit(inside), "pre-reset prepared plan committed");
    require(scheduler.prepare(1, &read, 1).ok, "reset did not recover scheduler");
    ScheduleResult reusable;
    scheduler.prepareInto(reusable, 1, &write, 1);
    require(reusable.ok && scheduler.commit(reusable), "reusable initial plan failed");
    const auto useCapacity = reusable.uses.capacity();
    auto invalid = read;
    invalid.version = 2;
    scheduler.prepareInto(reusable, 2, &invalid, 1);
    require(!reusable.ok && reusable.error != nullptr && reusable.uses.empty() &&
        reusable.dependencies.empty() && reusable.barriers.empty() &&
        reusable.uses.capacity() >= useCapacity && !scheduler.commit(reusable),
        "reusable rejected plan retained work or lost capacity");
    scheduler.prepareInto(reusable, 2, &read, 1);
    require(reusable.ok && reusable.error == nullptr && reusable.dependencies.size() == 1 &&
        scheduler.commit(reusable), "reusable plan did not recover after failure");
    std::puts("vanilla scheduler frontier probe passed");
}
