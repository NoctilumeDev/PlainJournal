package com.ecommerce.identity.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.identity.application.exception.IdentityError;
import com.ecommerce.identity.application.exception.IdentityException;
import com.ecommerce.identity.application.model.AddressModels.AddressCommand;
import com.ecommerce.identity.application.model.AddressModels.AddressView;
import com.ecommerce.identity.infrastructure.persistence.entity.UserAddressEntity;
import com.ecommerce.identity.infrastructure.persistence.mapper.UserAccountMapper;
import com.ecommerce.identity.infrastructure.persistence.mapper.UserAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AddressService {

    private static final int MAX_ADDRESSES = 20;

    private final UserAddressMapper addressMapper;
    private final UserAccountMapper userMapper;
    private final Clock clock;

    public AddressService(UserAddressMapper addressMapper, UserAccountMapper userMapper, Clock clock) {
        this.addressMapper = addressMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Transactional
    public AddressView create(Long userId, AddressCommand command) {
        lockUser(userId);
        long count = addressMapper.selectCount(new LambdaQueryWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getUserId, userId));
        if (count >= MAX_ADDRESSES) {
            throw new IdentityException(IdentityError.ADDRESS_LIMIT_REACHED);
        }
        Instant now = clock.instant();
        boolean defaultAddress = count == 0 || command.setDefault();
        if (defaultAddress) {
            addressMapper.clearDefault(userId, now);
        }
        UserAddressEntity address = new UserAddressEntity();
        address.setId(IdWorker.getId());
        address.setUserId(userId);
        apply(address, command);
        address.setIsDefault(defaultAddress);
        address.setVersion(0);
        address.setCreatedAt(now);
        address.setUpdatedAt(now);
        addressMapper.insert(address);
        return view(address);
    }

    public List<AddressView> list(Long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<UserAddressEntity>()
                        .eq(UserAddressEntity::getUserId, userId)
                        .orderByDesc(UserAddressEntity::getIsDefault)
                        .orderByDesc(UserAddressEntity::getUpdatedAt))
                .stream().map(this::view).toList();
    }

    public AddressView getOwned(Long userId, Long addressId) {
        UserAddressEntity address = addressMapper.selectOne(new LambdaQueryWrapper<UserAddressEntity>()
                .eq(UserAddressEntity::getId, addressId)
                .eq(UserAddressEntity::getUserId, userId));
        if (address == null) {
            throw new IdentityException(IdentityError.ADDRESS_NOT_FOUND);
        }
        return view(address);
    }

    @Transactional
    public AddressView update(Long userId, Long addressId, AddressCommand command) {
        lockUser(userId);
        UserAddressEntity address = requireLocked(userId, addressId);
        Instant now = clock.instant();
        if (command.setDefault()) {
            addressMapper.clearDefault(userId, now);
            address.setIsDefault(true);
        }
        apply(address, command);
        address.setUpdatedAt(now);
        requireUpdated(addressMapper.updateById(address));
        return view(address);
    }

    @Transactional
    public AddressView setDefault(Long userId, Long addressId) {
        lockUser(userId);
        UserAddressEntity address = requireLocked(userId, addressId);
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            return view(address);
        }
        Instant now = clock.instant();
        addressMapper.clearDefault(userId, now);
        address.setIsDefault(true);
        address.setUpdatedAt(now);
        requireUpdated(addressMapper.updateById(address));
        return view(address);
    }

    @Transactional
    public void delete(Long userId, Long addressId) {
        lockUser(userId);
        UserAddressEntity address = requireLocked(userId, addressId);
        boolean wasDefault = Boolean.TRUE.equals(address.getIsDefault());
        addressMapper.deleteById(address.getId());
        if (wasDefault) {
            UserAddressEntity replacement = addressMapper.selectOne(
                    new LambdaQueryWrapper<UserAddressEntity>()
                            .eq(UserAddressEntity::getUserId, userId)
                            .orderByDesc(UserAddressEntity::getUpdatedAt)
                            .last("LIMIT 1"));
            if (replacement != null) {
                replacement.setIsDefault(true);
                replacement.setUpdatedAt(clock.instant());
                requireUpdated(addressMapper.updateById(replacement));
            }
        }
    }

    private void lockUser(Long userId) {
        if (userMapper.lockUser(userId) == null) {
            throw new IdentityException(IdentityError.ACCOUNT_NOT_FOUND);
        }
    }

    private UserAddressEntity requireLocked(Long userId, Long addressId) {
        UserAddressEntity address = addressMapper.selectOwnedForUpdate(userId, addressId);
        if (address == null) {
            throw new IdentityException(IdentityError.ADDRESS_NOT_FOUND);
        }
        return address;
    }

    private void apply(UserAddressEntity address, AddressCommand command) {
        address.setRecipientName(command.recipientName().trim());
        address.setPhone(command.phone().trim());
        address.setProvince(command.province().trim());
        address.setProvinceCode(command.provinceCode().trim());
        address.setCity(command.city().trim());
        address.setCityCode(command.cityCode().trim());
        address.setDistrict(command.district().trim());
        address.setDistrictCode(command.districtCode().trim());
        address.setDetailAddress(command.detailAddress().trim());
        address.setPostalCode(command.postalCode() == null || command.postalCode().isBlank()
                ? null : command.postalCode().trim());
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new IdentityException(IdentityError.CONCURRENT_MODIFICATION);
        }
    }

    private AddressView view(UserAddressEntity address) {
        return new AddressView(address.getId(), address.getRecipientName(), address.getPhone(),
                address.getProvince(), address.getProvinceCode(), address.getCity(), address.getCityCode(),
                address.getDistrict(), address.getDistrictCode(), address.getDetailAddress(),
                address.getPostalCode(), Boolean.TRUE.equals(address.getIsDefault()), address.getVersion(),
                address.getCreatedAt(), address.getUpdatedAt());
    }
}
