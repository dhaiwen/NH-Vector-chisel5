/***************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  ***************************************************************************************/

package xiangshan.vector.vtyperename

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.vector._
import xiangshan.backend.rob._
import xs.utils._
import xiangshan.backend.execute.fu.FuOutput

class VTypeEntry(implicit p: Parameters) extends VectorBaseBundle {
  val vill = Bool()
  val info = new VICsrInfo()
  val robIdx = new RobPtr
  val writebacked = Bool()
}

class VTypeResp(implicit p: Parameters) extends VectorBaseBundle{
  val vtype = new VICsrInfo()
  val state = Bool()
  val vtypeIdx = UInt(VIVtypeRegsNum.W)
  val robIdx = new RobPtr
}

class VTypeRenameTable(size:Int)(implicit p: Parameters) extends XSModule{
  val io = IO(new Bundle{
    val w = Input(Vec(RenameWidth + 1, new Bundle{
      val en = Bool()
      val data = new VTypeEntry
      val addr = UInt(log2Ceil(size).W)
    }))
    val r = Vec(2, new Bundle{
      val addr = Input(UInt(log2Ceil(size).W))
      val data = Output(new VTypeEntry)
    })
    val redirect = Input(Valid(new Redirect))
    val flushVec = Output(UInt(size.W))
  })
  private val table = Reg(Vec(size, new VTypeEntry))

  for((entry, idx) <- table.zipWithIndex){
    val hitSeq = io.w.map(w => w.en && w.addr === idx.U)
    val dataSeq = io.w.map(_.data)
    val data = Mux1H(hitSeq, dataSeq)
    val en = hitSeq.reduce(_|_)
    when(en){
      entry := data
    }
  }

  for(r <- io.r){
    val rVec = table.indices.map(_.U === r.addr)
    r.data := Mux1H(rVec, table)
  }

  io.flushVec := Cat(table.map(_.robIdx.needFlush(io.redirect)).reverse)
}

class VtypeRename(implicit p: Parameters) extends VectorBaseModule with HasCircularQueuePtrHelper {

  val io = IO(new Bundle() {
    val redirect = Flipped(ValidIO(new Redirect))
    val robCommits = Flipped(new RobCommitIO)
    val canAllocate = Output(Bool())
    val in = Vec(RenameWidth, Flipped(ValidIO(new MicroOp)))
    val out = Vec(RenameWidth, ValidIO(new VTypeResp))
//    val deq = Vec(VICommitWidth, DecoupledIO(new MicroOp))
    val writeback = Flipped(ValidIO(new FuOutput(64)))
  })


  private val table = Module(new VTypeRenameTable(VIVtypeRegsNum))
  private val enqPtr = RegInit(0.U.asTypeOf(new VtypePtr))
  private val deqPtr = RegInit(0.U.asTypeOf(new VtypePtr))
  assert(enqPtr >= deqPtr)

  class VtypePtr extends CircularQueuePtr[VtypePtr](VIVtypeRegsNum)

  private val validEntriesNum = distanceBetween(enqPtr, deqPtr)
  private val emptyEntiresNum = VIVtypeRegsNum.U - validEntriesNum
  private val enqMask = UIntToMask(enqPtr.value, VIVtypeRegsNum)
  private val deqMask = UIntToMask(deqPtr.value, VIVtypeRegsNum)
  private val enqXorDeq = enqMask ^ deqMask
  private val validsMask = Mux(deqPtr.value <= enqPtr.value, enqXorDeq, (~enqXorDeq).asUInt)
  private val redirectMask = validsMask & table.io.flushVec
  private val flushNum = PopCount(redirectMask)

  private val setVlSeq = io.in.map(i => i.valid && i.bits.ctrl.isVtype)
  private val setVlNum = PopCount(setVlSeq)
  io.canAllocate := setVlNum <= emptyEntiresNum

  private val realValids = setVlSeq.map(_ && io.canAllocate)
  table.io.redirect := io.redirect

  table.io.w.last.en := io.writeback.valid
  table.io.w.last.data.info.vma := io.writeback.bits.data(7)
  table.io.w.last.data.info.vta := io.writeback.bits.data(6)
  table.io.w.last.data.info.vsew := io.writeback.bits.data(5, 3)
  table.io.w.last.data.info.vlmul := io.writeback.bits.data(2, 0)
  table.io.w.last.data.info.vl := io.writeback.bits.data(15, 8)
  table.io.w.last.data.vill := io.writeback.bits.data(XLEN - 1)
  table.io.w.last.data.info.vlmax := table.io.w.last.data.info.VLMAXGen()
  table.io.w.last.data.writebacked := true.B
  table.io.w.last.data.robIdx := io.writeback.bits.uop.robIdx
  table.io.w.last.addr := io.writeback.bits.uop.vtypeRegIdx

  private val enqAddrEnqSeq = Wire(Vec(RenameWidth, new VtypePtr))
  private val vtypeEnqSeq = Wire(Vec(RenameWidth, new VTypeEntry))

  table.io.r(0).addr := (enqPtr - 1.U).value
  private val oldVType = WireInit(table.io.r(0).data)

  table.io.r(1).addr := deqPtr.value
  private val actualVl = Cat(Seq(
    table.io.r(1).data.vill,
    0.U((XLEN - 17).W),
    table.io.r(1).data.info.vl,
    table.io.r(1).data.info.vma,
    table.io.r(1).data.info.vta,
    table.io.r(1).data.info.vsew,
    table.io.r(1).data.info.vlmul
  ))

  private def GenVType(in:MicroOp):VTypeEntry = {
    val res = Wire(new VTypeEntry())
    //TODO:Fill this Function
    res := DontCare
    res.robIdx := in.robIdx
    res
  }

  realValids.zipWithIndex.foreach({case(s, idx) =>
    val newVType = GenVType(io.in(idx).bits)
    if(idx == 0){
      enqAddrEnqSeq(idx) := enqPtr
      vtypeEnqSeq(idx) := Mux(s, newVType, oldVType)
    } else {
      enqAddrEnqSeq(idx) := Mux(s, enqAddrEnqSeq(idx - 1) + 1.U, enqAddrEnqSeq(idx - 1))
      vtypeEnqSeq(idx) := Mux(s, newVType, vtypeEnqSeq(idx - 1))
    }
  })

  for((((w, addr), data), en) <- table.io.w.zip(enqAddrEnqSeq).zip(vtypeEnqSeq).zip(realValids)){
    w.en := en
    w.data := data
    w.addr := addr.value
  }

  private val actualEnqNum = PopCount(realValids)
  when(io.redirect.valid) {
    enqPtr := enqPtr - flushNum
  }.elsewhen(actualEnqNum =/= 0.U) {
    enqPtr := enqPtr + actualEnqNum
  }

  private val setVlCommSeq = io.robCommits.commitValid.zip(io.robCommits.info).map({case(a, b) => a && b.wvcsr})
  private val setVlCommitted = io.robCommits.isCommit && setVlCommSeq.reduce(_|_)
  private val commmitNum = PopCount(setVlCommSeq)
  private val comValidReg = RegNext(setVlCommitted, false.B)
  private val comNumReg = RegEnable(commmitNum, setVlCommitted)
  when(comValidReg){
    deqPtr := deqPtr + comNumReg
  }

  for (i <- 0 until RenameWidth) {
    io.out(i).valid := io.in(i).valid && io.in(i).bits.ctrl.isVector && io.in(i).bits.ctrl.isVtype
    io.out(i).bits.vtype := vtypeEnqSeq(i).info
    io.out(i).bits.vtypeIdx := enqAddrEnqSeq(i).value
    io.out(i).bits.state := vtypeEnqSeq(i).writebacked
    io.out(i).bits.robIdx := io.in(i).bits.robIdx
  }
}
