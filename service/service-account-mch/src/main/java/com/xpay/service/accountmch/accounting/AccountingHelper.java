package com.xpay.service.accountmch.accounting;

import com.xpay.common.statics.constants.common.PublicStatus;
import com.xpay.common.statics.constants.mqdest.TopicDest;
import com.xpay.common.statics.constants.mqdest.TopicGroup;
import com.xpay.common.statics.dto.account.AccountProcessDto;
import com.xpay.common.statics.dto.account.AdvanceClearMsgDto;
import com.xpay.common.statics.enums.account.AccountProcessTypeEnum;
import com.xpay.common.statics.enums.product.ProductCodeEnum;
import com.xpay.common.statics.enums.product.ProductTypeEnum;
import com.xpay.common.statics.exception.AccountMchBizException;
import com.xpay.common.statics.exception.BizException;
import com.xpay.common.utils.*;
import com.xpay.facade.accountmch.dto.OrderCountDto;
import com.xpay.service.accountmch.accounting.processors.AccountingProcessor;
import com.xpay.service.accountmch.bo.AccountingBo;
import com.xpay.service.accountmch.config.AccountingConfig;
import com.xpay.service.accountmch.dao.AccountAdvanceClearDao;
import com.xpay.service.accountmch.dao.AccountBalanceSnapDao;
import com.xpay.service.accountmch.dao.AccountMchDao;
import com.xpay.service.accountmch.dao.AccountProcessDetailDao;
import com.xpay.service.accountmch.entity.AccountAdvanceClear;
import com.xpay.service.accountmch.entity.AccountBalanceSnap;
import com.xpay.service.accountmch.entity.AccountMch;
import com.xpay.service.accountmch.entity.AccountProcessDetail;
import com.xpay.starter.plugin.plugins.MQSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
public class AccountingHelper {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    public final static String ACCOUNT_UNIQUE_TABLE_NAME = "tbl_account_common_unique";
    @Autowired
    private AccountMchDao accountMchDao;
    @Autowired
    private AccountProcessDetailDao accountProcessDetailDao;
    @Autowired
    private AccountBalanceSnapDao accountBalanceSnapDao;
    @Autowired
    private AccountAdvanceClearDao accountAdvanceClearDao;
    @Autowired
    private MQSender mqSender;

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * @param curTime
     * @param account
     * @param snapNo
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean doBalanceSnap(long curTime, AccountMch account, String snapNo) {
        if(curTime < DateUtil.secondToMillSecond(account.getNextSnapTime())){
            return false;
        }else if(StringUtil.isEmpty(snapNo)){
            snapNo = RandomUtil.get32LenStr();
        }

        int snapTimeOld = account.getNextSnapTime();
        Date nextSnapDate = DateUtil.addDay(new Date(), 1);//???1???
        account.setNextSnapTime(DateUtil.getDayStartSecond(nextSnapDate));

        AccountBalanceSnap snap = new AccountBalanceSnap();
        snap.setCreateTime(new Date());
        snap.setVersion(0);
        snap.setSnapDate(snap.getCreateTime());
        snap.setAccountNo(account.getAccountNo());
        snap.setSnapNo(snapNo);
        snap.setBalance(account.getBalance());
        snap.setSettledAmount(account.getSettledAmount());
        snap.setUnsettleAmount(account.getUnsettleAmount());
        snap.setRsmAmount(account.getRsmAmount());
        snap.setTotalCreditAmount(account.getTotalCreditAmount());
        snap.setTotalReturnAmount(account.getTotalReturnAmount());
        snap.setTotalDebitAmount(account.getTotalDebitAmount());
        snap.setAdvanceRatio(account.getAdvanceRatio());
        snap.setMaxAdvanceAmount(account.getMaxAvailAdvanceAmount());
        snap.setTotalAdvanceAmount(account.getTotalAdvanceAmount());
        snap.setAdvanceAmount(account.getAvailAdvanceAmount());
        snap.setRetainAmount(account.getRetainAdvanceAmount());
        snap.setTotalOrderCount(account.getTotalOrderCount());

        try{
            accountBalanceSnapDao.insert(snap);//??????????????????
            accountMchDao.updateAccountSnapTime(account.getAccountNo(), account.getNextSnapTime(), snapTimeOld);//??????????????????????????????????????????
            account.setVersion(account.getVersion() + 1);
            return true;
        }catch(Exception e){
            logger.error("????????????????????????????????? accountNo={} snapNo={}", account.getAccountNo(), snapNo, e);
            throw new AccountMchBizException(AccountMchBizException.ACCOUNT_SNAPSHOT_FAIL, "??????????????????????????????");
        }
    }

    /**
     * ??????????????????
     * @param curTime
     * @param account
     * @param clearNo
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean doAdvanceClear(long curTime, AccountMch account, String clearNo) {
        if(curTime < DateUtil.secondToMillSecond(account.getNextLiquidateTime())){
            return false;
        }else if(StringUtil.isEmpty(clearNo)){
            clearNo = RandomUtil.get16LenStr();
        }

        Date clearTime = new Date();
        AccountAdvanceClear clearRecord = new AccountAdvanceClear();
        clearRecord.setCreateTime(clearTime);
        clearRecord.setCreateDate(clearRecord.getCreateTime());
        clearRecord.setAccountNo(account.getAccountNo());
        clearRecord.setClearNo(clearNo);
        clearRecord.setBalance(account.getBalance());
        clearRecord.setSettleAmount(account.getSettledAmount());
        clearRecord.setUnsettleAmount(account.getUnsettleAmount());
        clearRecord.setTotalAdvanceAmount(account.getTotalAdvanceAmount());
        clearRecord.setAdvanceAmount(account.getAvailAdvanceAmount());
        clearRecord.setRetainAmount(account.getAvailAdvanceAmount());
        clearRecord.setRsmAmount(account.getRsmAmount());
        clearRecord.setTotalCreditAmount(account.getTotalCreditAmount());
        clearRecord.setTotalReturnAmount(account.getTotalReturnAmount());
        clearRecord.setTotalDebitAmount(account.getTotalDebitAmount());
        clearRecord.setRemainAmount(AmountUtil.sub(AmountUtil.add(account.getTotalCreditAmount(), account.getTotalReturnAmount()),
                account.getTotalDebitAmount()));
        clearRecord.setAdvanceRatio(account.getAdvanceRatio());
        clearRecord.setMaxAdvanceAmount(account.getAvailAdvanceAmount());
        clearRecord.setClearRound(clearRecord.getCreateTime());

        OrderCountDto countDto = OrderCountDto.fromAccountOrderCount(account.getTotalOrderCount());
        clearRecord.setTotalReceiveCount(countDto.getTotalReceiveCount());
        clearRecord.setTotalReturnCount(countDto.getTotalReturnCount());
        clearRecord.setTotalDebitCount(countDto.getTotalDebitCount());

        AccountMch accountOld = BeanUtil.newAndCopy(account, AccountMch.class);

        //????????????????????????????????????????????????????????????
        account.setUnsettleAmount(AmountUtil.add(account.getUnsettleAmount(), account.getTotalAdvanceAmount()));
        //???????????????????????????????????????
        account.setTotalCreditAmount(BigDecimal.ZERO);
        account.setTotalDebitAmount(BigDecimal.ZERO);
        account.setTotalReturnAmount(BigDecimal.ZERO);
        account.setTotalAdvanceAmount(BigDecimal.ZERO);
        account.setAvailAdvanceAmount(BigDecimal.ZERO);
        account.setRetainAdvanceAmount(BigDecimal.ZERO);
        account.setCurrentAdvanceAmount(BigDecimal.ZERO);
        account.setTotalAdvanceDebitAmount(BigDecimal.ZERO);
        account.setTotalAdvanceReturnAmount(BigDecimal.ZERO);
        account.setTotalOrderCount(AccountingConfig.DEFAULT_TOTAL_ORDER_COUNT);
        account.setLiquidateVersion(account.getLiquidateVersion() + 1);//??????????????? + 1
        Date nextClearDate = DateUtil.addDay(new Date(), 1);//???????????????,??? D+1 ????????????
        account.setNextLiquidateTime(DateUtil.getDayStartSecond(nextClearDate));

        AccountProcessDto processDto = new AccountProcessDto();
        processDto.setTrxTime(clearTime);
        processDto.setTrxNo(clearNo);
        processDto.setMchTrxNo(clearNo);
        processDto.setProcessType(AccountProcessTypeEnum.SELF_CIRCULATION.getValue());
        processDto.setAmountType(0);//????????????
        processDto.setAccountNo(account.getAccountNo());
        processDto.setAmount(BigDecimal.ZERO);
        processDto.setFee(BigDecimal.ZERO);
        processDto.setBussType(ProductTypeEnum.SETTLE.getValue());
        processDto.setBussCode(ProductCodeEnum.SETTLE_ADVANCE_CLEAR.getValue());
        processDto.setDesc("?????????-????????????");
        processDto.setProcessNo(clearNo);
        processDto.setAccountTime(clearTime);

        AccountProcessDetail detail = AccountingProcessor.buildAccountProcessDetail(accountOld, account, processDto);
        //??????????????????????????????
        detail.setLiquidation(PublicStatus.INACTIVE);

        //???????????????????????????????????????MQ??????
        try {
            accountProcessDetailDao.uniqueInsert(detail);
            clearRecord.setClearAccountDetailId(detail.getId());

            accountAdvanceClearDao.insert(clearRecord);

            accountMchDao.update(account);

            //????????????????????????????????????????????????
            AdvanceClearMsgDto msgDto = new AdvanceClearMsgDto(TopicDest.MCH_ADVANCE_CLEAR, TopicGroup.MCH_SETTLE_GROUP);
            msgDto.setTrxNo(clearNo);
            msgDto.setAccountClearId(clearRecord.getId());
            mqSender.sendOne(msgDto);
            return true;
        } catch(Exception e) {
            logger.error("?????????????????????????????????????????? accountNo={} clearNo={}", account.getAccountNo(), clearNo, e);
            throw new AccountMchBizException(AccountMchBizException.ADVANCE_CLEAR_FAIL, "??????????????????");
        }
    }

    /**
     * ????????????????????????
     * @param accountingBo
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAccountingResult(AccountingBo accountingBo){
        for (Map.Entry<AccountMch, List<AccountProcessDetail>> entry : accountingBo.getAccountMapDetail().entrySet()) {
            AccountMch account = entry.getKey();
            List<AccountProcessDetail> accountDetailList = entry.getValue();
            try {
                accountMchDao.update(account);
                accountProcessDetailDao.uniqueInsert(accountDetailList);
            } catch (Throwable ex) {
                logger.error("????????????????????????????????????????????? accountNo={}", account.getAccountNo(), ex);
                if (! this.isAccountProcessRepeat(ex)) {
                    throw ex;
                }

                StringBuilder msg = new StringBuilder("accountNo: ").append(account.getAccountNo()).append(",trxNos: ");
                entry.getValue().forEach(v -> msg.append(v.getRequestNo()).append(","));
                msg.append("?????????????????????????????????");
                throw new AccountMchBizException(AccountMchBizException.ACCOUNT_PROCESS_REPEAT, msg.toString());
            }
        }
    }

    /**
     * ????????????????????????
     * @param e
     * @return
     */
    private static boolean isAccountProcessRepeat(Throwable e){
        if(e == null){
            return false;
        }

        String errorMsg = e.getMessage() == null ? "" : e.getMessage();
        if(e instanceof DuplicateKeyException && errorMsg.contains(ACCOUNT_UNIQUE_TABLE_NAME)){
            return true;
        }else if(errorMsg.contains("Duplicate entry") && errorMsg.contains(ACCOUNT_UNIQUE_TABLE_NAME)){
            return true;
        }else if(e instanceof BizException && ((BizException) e).getCode() == AccountMchBizException.ACCOUNT_PROCESS_REPEAT){
            return true;
        }else{
            return false;
        }
    }
}
