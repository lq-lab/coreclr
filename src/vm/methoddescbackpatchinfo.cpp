// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.
// See the LICENSE file in the project root for more information.

#include "common.h"

#include "excep.h"
#include "log.h"
#include "methoddescbackpatchinfo.h"

#ifdef CROSSGEN_COMPILE
    #error This file is not expected to be included into CrossGen
#endif

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// EntryPointSlots

#ifndef DACCESS_COMPILE

void EntryPointSlots::Backpatch_Locked(TADDR slot, SlotType slotType, PCODE entryPoint)
{
    WRAPPER_NO_CONTRACT;
    static_assert_no_msg(SlotType_Count <= sizeof(INT32));
    _ASSERTE(MethodDescBackpatchInfoTracker::IsLockedByCurrentThread());
    _ASSERTE(slot != NULL);
    _ASSERTE(!(slot & SlotType_Mask));
    _ASSERTE(slotType >= SlotType_Normal);
    _ASSERTE(slotType < SlotType_Count);
    _ASSERTE(entryPoint != NULL);
    _ASSERTE(IS_ALIGNED((SIZE_T)slot, GetRequiredSlotAlignment(slotType)));

    switch (slotType)
    {
        case SlotType_Normal:
            *(PCODE *)slot = entryPoint;
            break;

        case SlotType_Vtable:
            ((MethodTable::VTableIndir2_t *)slot)->SetValue(entryPoint);
            break;

        case SlotType_Executable:
            *(PCODE *)slot = entryPoint;
            goto Flush;

        case SlotType_ExecutableRel32:
            // A rel32 may require a jump stub on some architectures, and is currently not supported
            _ASSERTE(sizeof(void *) <= 4);

            *(PCODE *)slot = entryPoint - ((PCODE)slot + sizeof(PCODE));
            // fall through

        Flush:
            ClrFlushInstructionCache((LPCVOID)slot, sizeof(PCODE));
            break;

        default:
            UNREACHABLE();
            break;
    }
}

#endif // !DACCESS_COMPILE

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// MethodDescBackpatchInfoTracker

CrstStatic MethodDescBackpatchInfoTracker::s_lock;
bool MethodDescBackpatchInfoTracker::s_isLocked = false;

#ifndef DACCESS_COMPILE

void MethodDescBackpatchInfoTracker::Backpatch_Locked(MethodDesc *pMethodDesc, PCODE entryPoint)
{
    WRAPPER_NO_CONTRACT;
    _ASSERTE(IsLockedByCurrentThread());
    _ASSERTE(pMethodDesc != nullptr);

    GCX_COOP();

    auto lambda = [&entryPoint](OBJECTREF obj, MethodDesc *pMethodDesc, UINT_PTR slotData)
    {

        TADDR slot;
        EntryPointSlots::SlotType slotType;

        EntryPointSlots::ConvertUINT_PTRToSlotAndTypePair(slotData, &slot, &slotType);
        EntryPointSlots::Backpatch_Locked(slot, slotType, entryPoint);

        return true; // Keep walking
    };

    m_backpatchInfoHash.VisitValuesOfKey(pMethodDesc, lambda);
}

void MethodDescBackpatchInfoTracker::AddSlotAndPatch_Locked(MethodDesc *pMethodDesc, LoaderAllocator *pLoaderAllocatorOfSlot, TADDR slot, EntryPointSlots::SlotType slotType, PCODE currentEntryPoint)
{
    WRAPPER_NO_CONTRACT;
    _ASSERTE(IsLockedByCurrentThread());
    _ASSERTE(pMethodDesc != nullptr);
    _ASSERTE(pMethodDesc->MayHaveEntryPointSlotsToBackpatch());

    GCX_COOP();

    UINT_PTR slotData;
    slotData = EntryPointSlots::ConvertSlotAndTypePairToUINT_PTR(slot, slotType);

    m_backpatchInfoHash.Add(pMethodDesc, slotData, pLoaderAllocatorOfSlot);
    EntryPointSlots::Backpatch_Locked(slot, slotType, currentEntryPoint);
}

void MethodDescBackpatchInfoTracker::StaticInitialize()
{
    WRAPPER_NO_CONTRACT;
    s_lock.Init(CrstMethodDescBackpatchInfoTracker);
}

#endif // DACCESS_COMPILE

#ifdef _DEBUG

bool MethodDescBackpatchInfoTracker::IsLockedByCurrentThread()
{
    WRAPPER_NO_CONTRACT;

#ifndef DACCESS_COMPILE
    return !!s_lock.OwnedByCurrentThread();
#else
    return true;
#endif
}

bool MethodDescBackpatchInfoTracker::MayHaveEntryPointSlotsToBackpatch(PTR_MethodDesc methodDesc)
{
    // The only purpose of this method is to allow asserts in inline functions defined in the .h file, by which time MethodDesc
    // is not fully defined

    WRAPPER_NO_CONTRACT;
    return methodDesc->MayHaveEntryPointSlotsToBackpatch();
}

#endif // _DEBUG

#ifndef DACCESS_COMPILE
void MethodDescBackpatchInfoTracker::PollForDebuggerSuspension()
{
    CONTRACTL
    {
        NOTHROW;
        GC_TRIGGERS;
        MODE_PREEMPTIVE;
    }
    CONTRACTL_END;

    _ASSERTE(!IsLockedByCurrentThread());

    // If suspension is pending for the debugger, pulse the GC mode to suspend the thread here. Following this call, typically
    // the lock is acquired and the GC mode is changed, and suspending there would cause FuncEvals to fail (see
    // Debugger::FuncEvalSetup() at the reference to IsLockOwnedByAnyThread()). Since this thread is in preemptive mode, the
    // debugger may think it's already suspended and it would be unfortunate to suspend the thread with the lock held.
    Thread *thread = GetThread();
    _ASSERTE(thread != nullptr);
    if (thread->HasThreadState(Thread::TS_DebugSuspendPending))
    {
        GCX_COOP();
    }
}
#endif

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
