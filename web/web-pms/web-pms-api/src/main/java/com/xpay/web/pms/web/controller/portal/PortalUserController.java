package com.xpay.web.pms.web.controller.portal;

import com.xpay.common.statics.annotations.Permission;
import com.xpay.common.statics.constants.message.EmailSend;
import com.xpay.common.statics.enums.user.UserTypeEnum;
import com.xpay.common.statics.enums.user.UserStatusEnum;
import com.xpay.common.statics.exception.BizException;
import com.xpay.common.statics.query.PageQuery;
import com.xpay.common.statics.result.PageResult;
import com.xpay.common.statics.result.RestResult;
import com.xpay.common.utils.*;
import com.xpay.facade.merchant.dto.MerchantDto;
import com.xpay.facade.merchant.dto.MerchantDetailDto;
import com.xpay.facade.merchant.service.MerchantFacade;
import com.xpay.facade.user.dto.PortalOperateLogDto;
import com.xpay.facade.user.dto.PortalUserDto;
import com.xpay.facade.user.dto.PortalRoleDto;
import com.xpay.facade.user.service.PortalOperateLogFacade;
import com.xpay.facade.user.service.PortalUserFacade;
import com.xpay.facade.user.service.PortalPermissionFacade;
import com.xpay.web.api.common.annotations.CurrentUser;
import com.xpay.web.api.common.config.WebApiProperties;
import com.xpay.web.api.common.ddo.vo.UserVo;
import com.xpay.web.api.common.ddo.dto.AssignRoleDto;
import com.xpay.web.api.common.ddo.dto.EmailParamDto;
import com.xpay.web.api.common.ddo.dto.OperateLogQueryDto;
import com.xpay.web.api.common.ddo.dto.UserQueryDto;
import com.xpay.web.api.common.model.UserModel;
import com.xpay.web.api.common.service.CryptService;
import com.xpay.web.api.common.service.MessageService;
import com.xpay.web.pms.web.controller.BaseController;
import jakarta.validation.constraints.NotNull;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Description:
 */
@RestController
@RequestMapping("portalUser")
public class PortalUserController extends BaseController {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    WebApiProperties properties;
    @Autowired
    private MessageService messageService;
    @Autowired
    private CryptService cryptService;

    @DubboReference
    PortalUserFacade portalUserFacade;
    @DubboReference
    PortalPermissionFacade portalPermissionFacade;
    @DubboReference
    MerchantFacade merchantFacade;
    @DubboReference
    PortalOperateLogFacade portalOperateLogFacade;

    /**
     * ???????????????????????????????????????????????????????????????????????????.
     */
    @Permission("portal:user:view")
    @RequestMapping("listUser")
    public RestResult<PageResult<List<UserVo>>> listUser(@RequestBody UserQueryDto queryVo) {
        try {
            PageQuery pageQuery = PageQuery.newInstance(queryVo.getCurrentPage(), queryVo.getPageSize());
            Map<String, Object> paramMap = BeanUtil.toMapNotNull(queryVo);
            PageResult<List<PortalUserDto>> pageResult = portalUserFacade.listUserPage(paramMap, pageQuery);
            if (pageResult.getData() == null || pageResult.getData().isEmpty()) {
                PageResult<List<UserVo>> result = PageResult.newInstance(new ArrayList<>(), pageResult);
                return RestResult.success(result);
            }

            List<UserVo> userVoList = new ArrayList<>();
            for(PortalUserDto userDto : pageResult.getData()){
                UserVo vo = BeanUtil.newAndCopy(userDto, UserVo.class);
                vo.setLoginPwd("******");
                userVoList.add(vo);
            }
            PageResult<List<UserVo>> result = PageResult.newInstance(userVoList, pageResult);
            return RestResult.success(result);
        } catch (Exception e) {
            logger.error("??????????????????", e);
            return RestResult.error("??????????????????");
        }
    }

    @Permission("portal:user:view")
    @RequestMapping("getUserById")
    public RestResult<PortalUserDto> getUserById(@RequestParam Long userId) {
        PortalUserDto user = portalUserFacade.getUserById(userId);
        if(user != null){
            user.setLoginPwd("******");
            List<PortalRoleDto> pmsRoles = portalPermissionFacade.listRolesByUserId(userId, null);
            user.setRoleIds(pmsRoles.stream().map(PortalRoleDto::getId).collect(Collectors.toList()));
        }
        return RestResult.success(user);
    }

    @Permission("portal:user:view")
    @RequestMapping("userInfo")
    public RestResult<UserVo> userInfo(@RequestParam long userId) {
        PortalUserDto user = portalUserFacade.getUserById(userId);
        user.setLoginPwd("******");
        return RestResult.success(BeanUtil.newAndCopy(user, UserVo.class));
    }

    /**
     * ???????????????????????????????????????
     */
    @Permission("portal:user:addAdmin")
    @RequestMapping("searchMerchant")
    public RestResult<List<MerchantDto>> searchMerchant(@RequestParam String mchName){
        if(StringUtil.isEmpty(mchName)){
            return RestResult.error("????????????????????????");
        }

        PageQuery pageQuery = PageQuery.newInstance(1, 5);//????????????5???????????????????????????????????????????????????????????????????????????
        pageQuery.setIsNeedTotalRecord(false);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("mchName", mchName);
        PageResult<List<MerchantDto>> pageResult = merchantFacade.listMerchantPage(paramMap, pageQuery);
        return RestResult.success(pageResult.getData());
    }

    @Permission("portal:user:addAdmin")
    @RequestMapping("addAdminUser")
    public RestResult<String> addAdminUser(@RequestBody UserVo userVo, @CurrentUser UserModel userModel) {
        if(userVo == null){
            return RestResult.error("???????????????????????????");
        }else if(StringUtil.isEmpty(userVo.getLoginName())){
            return RestResult.error("????????????????????????");
        }else if(StringUtil.isEmpty(userVo.getMchNo())){
            return RestResult.error("???????????????????????????");
        }

        try {
            PortalUserDto userTemp = portalUserFacade.getUserByLoginName(userVo.getLoginName());
            if (userTemp != null) {
                return RestResult.error("???????????????????????????");
            }

            String mchNo = userVo.getMchNo();
            MerchantDto merchant = merchantFacade.getMerchantByMerchantNo(mchNo);
            if(merchant == null){
                return RestResult.error("????????????????????????");
            }

            userVo.setMchType(merchant.getMchType());
            String oriLoginPwd = RandomUtil.genRandomPwd(8);//????????????????????????
            String loginPwd = cryptService.encryptSha1(oriLoginPwd);

            PortalUserDto portalUser = BeanUtil.newAndCopy(userVo, PortalUserDto.class);
            portalUser.setType(UserTypeEnum.ADMIN.getValue());//??????????????????????????????
            portalUser.setCreator(userModel.getLoginName());
            portalUser.setModifier(userModel.getLoginName());
            portalUser.setRoleIds(userVo.getRoleIds());
            portalUser.setLoginPwd(loginPwd);
            portalUserFacade.createUser(portalUser);
            super.logAdd("???????????????????????????[" + portalUser.getLoginName() + "]", userModel);

            //????????????????????????
            try{
                platformAccountBuildMailNotify(userVo.getMchNo(), userVo.getLoginName(), oriLoginPwd);
            }catch(Exception e){
                logger.error("????????????????????????????????????????????? mchNo={} loginName={}", userVo.getMchNo(), userVo.getLoginName(), e);
                return RestResult.warn("???????????????????????????????????????????????????");
            }

            return RestResult.success("????????????");
        } catch (BizException e) {
            return RestResult.error("???????????????" + e.getMsg());
        } catch (Exception e) {
            logger.error("????????????????????????", e);
            return RestResult.error("??????????????????????????????");
        }
    }

    @Permission("portal:user:edit")
    @RequestMapping("editUser")
    public RestResult<String> editUser(@RequestBody UserVo userVo, @CurrentUser UserModel userModel) {
        try {
            PortalUserDto portalUser = portalUserFacade.getUserById(userVo.getId());
            if (portalUser == null) {
                return RestResult.error("????????????????????????????????????");
            }
            BeanUtil.copy(userVo, portalUser);
            portalUser.setModifier(userModel.getLoginName());// ?????????
            portalUserFacade.updateUser(portalUser);
            super.logEdit("???????????????????????????" + JsonUtil.toJson(portalUser), userModel);
            return RestResult.success("????????????");
        } catch (Exception e) {
            logger.error("????????????????????????", e);
            return RestResult.error("??????????????????????????????");
        }
    }

    /**
     * ??????????????????????????????????????????
     * @param assignDto
     * @param userModel
     * @return
     */
    @Permission("portal:user:assignAdminRoles")
    @RequestMapping("assignAdminRoles")
    public RestResult<String> assignAdminRoles(@NotNull @RequestBody AssignRoleDto assignDto, @CurrentUser UserModel userModel){
        Long userId = assignDto.getUserId();
        List<Long> roleIds = assignDto.getRoleIds();
        if(userId == null){
            return RestResult.success("???????????????????????????????????????????????????");
        }

        PortalUserDto userEntry = portalUserFacade.getUserById(userId);
        if (userEntry == null) {
            return RestResult.error("???????????????");
        }else if (UserTypeEnum.ADMIN.getValue() != userEntry.getType()) {
            return RestResult.error("??????????????????????????????????????????");
        }

        try {
            portalUserFacade.assignAdminRoles(userId, roleIds, userModel.getLoginName(), "???????????????????????????");
            super.logEdit("?????????????????????????????????[" + userEntry.getLoginName() + "]??????????????????[" + roleIds + "]", userModel);
            return RestResult.success("????????????");
        } catch(BizException e) {
            return RestResult.error("???????????????"+e.getMsg());
        } catch(Exception e) {
            logger.error("???????????????????????????????????????", e);
            return RestResult.error("???????????????????????????");
        }
    }

    @RequestMapping("changeStatus")
    @Permission("portal:user:changeStatus")
    public RestResult<String> changeStatus(@RequestParam Long userId, @CurrentUser UserModel userModel) {
        try {
            PortalUserDto user = portalUserFacade.getUserById(userId);
            if (user == null) {
                return RestResult.error("??????????????????????????????");
            }
            user.setStatus(user.getStatus() == UserStatusEnum.ACTIVE.getValue() ? UserStatusEnum.INACTIVE.getValue() : UserStatusEnum.ACTIVE.getValue());
            user.setModifier(userModel.getLoginName());
            portalUserFacade.updateUser(user);
            super.logEdit("??????/????????????[" + user.getLoginName() + "],???????????????:" + user.getStatus(), userModel);
            return RestResult.success("????????????");
        } catch (Exception e) {
            logger.error("??????/??????????????????", e);
            return RestResult.error("????????????");
        }
    }

    @RequestMapping("listOperateLog")
    @Permission("portal:operateLog:view")
    public RestResult<PageResult<List<PortalOperateLogDto>>> listOperateLog(@RequestBody OperateLogQueryDto queryDto) {
        if(queryDto == null && StringUtil.isEmpty(queryDto.getMchNo())){
            throw new BizException("????????????????????????");
        }
        PageQuery pageQuery = PageQuery.newInstance(queryDto.getCurrentPage(), queryDto.getPageSize());
        Map<String, Object> paramMap = BeanUtil.toMapNotNull(queryDto);
        PageResult<List<PortalOperateLogDto>> pageResult = portalOperateLogFacade.listOperateLogPage(paramMap, pageQuery);
        return RestResult.success(pageResult);
    }

    /**
     * ?????????????????????????????????????????????
     * @param mchNo
     * @param loginName
     */
    private void platformAccountBuildMailNotify(String mchNo, String loginName, String loginPwd) {
        MerchantDto merchant = merchantFacade.getMerchantByMerchantNo(mchNo);
        MerchantDetailDto merchantDetail = merchantFacade.getDetailByMerchantNo(mchNo);

        EmailParamDto emailDto = new EmailParamDto();
        emailDto.setFrom(EmailSend.MCH_NOTIFY);
        emailDto.setTo(merchantDetail.getBussContactEmail());
        emailDto.setCc(null);
        emailDto.setSubject("????????????????????????");
        emailDto.setTpl("platformAdminUserAdd.ftl");

        Map<String, Object> tplParam = new HashMap<>();
        tplParam.put("fullName", merchant.getFullName());
        tplParam.put("loginName", loginName);
        tplParam.put("loginPwd", loginPwd);
        tplParam.put("platformUrl", properties.getPlatformUrl());
        tplParam.put("servicePhone", properties.getServicePhone());

        emailDto.setTplParam(tplParam);
        emailDto.setHtmlFormat(false);
        messageService.sendEmailAsync(emailDto);
    }
}
