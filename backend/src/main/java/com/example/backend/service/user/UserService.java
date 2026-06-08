package com.example.backend.service.user;

import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.user.UserResponseDto;
import com.example.backend.dto.user.UserSaveRequestDto;
import com.example.backend.dto.user.UserUpdateRequestDto;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<UserResponseDto> findAll() {
        return userRepository.findAll().stream()
                .map(UserResponseDto::from)
                .toList();
    }

    public UserResponseDto findOne(Long id) {
        return userRepository.findById(id)
                .map(UserResponseDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional
    public UserResponseDto save(UserSaveRequestDto dto) {
        User user = new User(
                dto.getUsername(),
                passwordEncoder.encode(dto.getPassword()),
                dto.getEmail());
        return UserResponseDto.from(userRepository.save(user));
    }

    @Transactional
    public UserResponseDto update(Long id, UserUpdateRequestDto dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        if (dto.getUsername() != null) user.changeUsername(dto.getUsername());
        if (dto.getEmail() != null) user.changeEmail(dto.getEmail());
        return UserResponseDto.from(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User", id);
        }
        userRepository.deleteById(id);
    }
}
