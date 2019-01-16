package pl.mbab.subjectdeclaration.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.mbab.subjectdeclaration.model.Role;
import pl.mbab.subjectdeclaration.model.User;
import pl.mbab.subjectdeclaration.model.student.Semester;
import pl.mbab.subjectdeclaration.model.subject.Course;
import pl.mbab.subjectdeclaration.model.subject.FieldSubject;
import pl.mbab.subjectdeclaration.repository.CourseRepository;
import pl.mbab.subjectdeclaration.repository.UserRepository;
import pl.mbab.subjectdeclaration.web.dto.UserRegistrationDto;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CourseService courseService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new UsernameNotFoundException("Invalid username or password.");
        }
        return new org.springframework.security.core.userdetails.User(user.getEmail(),
                user.getPassword(),
                mapRolesToAuthorities(user.getRoles()));
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Transactional
    public User save(UserRegistrationDto registration) {
        User user = new User();
        user.setFirstName(registration.getFirstName());
        user.setLastName(registration.getLastName());
        user.setEmail(registration.getEmail());
        user.setGender(registration.getGender());
        user.setPesel(registration.getPesel());
        user.setSemester(registration.getSemester());
        user.setField(registration.getField());
        user.setPassword(passwordEncoder.encode(registration.getPassword()));
        user.setRoles(Arrays.asList(new Role("ROLE_USER")));
        userRepository.save(user);
        addCompulsoryCourse(user);
        return user;
    }

    private Collection<? extends GrantedAuthority> mapRolesToAuthorities(Collection<Role> roles) {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.getName()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addCourse(String login, Long courseId) {
        User user = findByEmail(login);
        Course course = courseService.findCourseById(courseId);
        Set<Course> addedCourses = user.getCourseBasket();
        for (Course addedCourse : addedCourses) {
            if (addedCourse.getSubject().getSignature().equals(course.getSubject().getSignature()) &&
                    addedCourse.getType().equals(course.getType())) {
                throw new RuntimeException("Przedmiot " + course.getSubject().getName() +
                        "został już dodany do koszyka");
            }
            if (addedCourse.getDay().equals(course.getDay()) &&
                    addedCourse.getStartTime().equals(course.getStartTime())) {
                throw new RuntimeException("Przedmiot " + course.getSubject().getName() +
                        " koliduje terminem zajęć z przedmiotem " + addedCourse.getSubject().getName());
            }
        }
        addedCourses.add(course);
    }

    @Override
    @Transactional
    public void deleteCourse(String login, Long courseId) {
        User user = findByEmail(login);
        Course course = courseService.findCourseById(courseId);
        Set<Course> userCourses = user.getCourseBasket();
        userCourses.remove(course);
    }

    @Override
    public Set<Course> showBasket(String login) {
        User user = findByEmail(login);
        return user.getCourseBasket();
    }

    @Override
    @Transactional
    public void addCompulsoryCourse(User user) {
        //        user.setCourseBasket(new LinkedHashSet<>());
        Set<Course> userCourses = user.getCourseBasket();
        userCourses.add(courseRepository.findById(Long.valueOf(user.getSemester().ordinal() + 1)).orElse(null));
    }

    @Override
    public Set<Course> getFieldCourses(String login, boolean group1) {
        User user = findByEmail(login);
        Set<FieldSubject> allSubjects = user.getField().getFieldSubjects();
        Set<String> newString = allSubjects.stream()
                .filter(s -> s.isRequired() == group1)
                .map(s -> s.getSubject().getSignature())
                .collect(Collectors.toSet());
        Set<Course> courses = courseService.getAllCourses();
        Set<Course> fieldCourses = courses.stream()
                .filter(s -> newString.contains(s.getSubject().getSignature()))
                .sorted((s, t) -> s.getSubject().getSignature().compareTo(t.getSubject().getSignature()))
                .collect(Collectors.toSet());

        return fieldCourses;
    }

    public Course[][] timetable(String login) {
        User user = findByEmail(login);
        Set<Course> basket = user.getCourseBasket();
        Course[][] coursesInBasket = new Course[7][5];
        for (Course singleCourse : basket) {
            coursesInBasket[parser(singleCourse.getStartTime())]
                    [singleCourse.getDay().ordinal()] = singleCourse;
        }
        System.out.println(Arrays.deepToString(coursesInBasket));
        return coursesInBasket;
    }

    public static int parser(LocalTime localTime) {
        for (int i = 0; i < 7; i++) {
            if (localTime.equals(LocalTime.of(8, 00, 00).plusMinutes(110 * i)))
                return i;
        }
        throw new RuntimeException("Nieprawidłowa godzina rozpoczęcia zajęć.");
    }

    public void checkEcts(User user){
        Set<Course> basket = user.getCourseBasket();
        double ectsInBasket = courseService.countEcts(basket);
        if(ectsInBasket < Semester.ectsMin) {
            throw new RuntimeException("Za mało przedmiotów dodanych do koszyka. Dodaj jeszcze przedmioty" +
                    " o wartości co najmniej " +(Semester.ectsMin - ectsInBasket) + "pkt ECTS");
        }
        if(ectsInBasket > Semester.ectsMax) {
            throw new RuntimeException("Za dużo przedmiotów dodanych do koszyka. Usuń z koszyka przedmioty" +
                    " o wartości co najmniej " +(ectsInBasket - Semester.ectsMax) + "pkt ECTS");
        }
    }

    public void checkComplexCourses(User user) {
        Set<Course> basket = user.getCourseBasket();
        Set<Course> newBasket = new HashSet<>(basket);

        newBasket = newBasket.stream().filter(course -> course.getSubject().isComplex() == true)
                .collect(Collectors.toSet());
        Map<String, List<Course>> courses = newBasket.stream()
                .collect(Collectors.groupingBy(course -> course.getSubject().getSignature()));

        for (String signature : courses.keySet()) {
            List<Course> coursesPerSignature = courses.get(signature);
            long number = coursesPerSignature.stream().count();
            if(number != 2L) {
                throw new RuntimeException("Do przedmiotu o sygnaturze" + signature + "brakuje wykładu, ćwiczeń");
            }
        }
    }
    public void checkFieldCourses(User user, boolean group1) {
        String login = user.getEmail();
        Set<Course> basket = user.getCourseBasket();
        Set<Course> newBasket = new HashSet<>(basket);
        Map<String, Course> tempMap = new HashMap<>();
        for (Course course : newBasket) {
            tempMap.put(course.getSubject().getSignature(), course);
        }
        Set<Course> uniqueSet = tempMap.values().stream().collect(Collectors.toSet());
        Set<Course> field = getFieldCourses(login, group1);
//        Set<Course> field2 = getFieldCourses(login, false);

        Set<Course> common = uniqueSet.stream().filter(course -> field.contains(course)).collect(Collectors.toSet());
        if(group1) {
            if (courseService.countEcts(common) < user.getSemester().getField1Ects()) {
                throw new RuntimeException("Za mało przedmiotów kierunkowych w koszyku. Dodaj jescze przedmioty" +
                        "kierunkowe o wartości przynajmniej " +
                        (user.getSemester().getField1Ects() - courseService.countEcts(common)) + " ects.");
            }
        }
        else {
            if (courseService.countEcts(common) < user.getSemester().getField2Ects()) {
                throw new RuntimeException("Za mało przedmiotów związanych z kierunkiem w koszyku." +
                        " Dodaj jescze przedmioty związane z kierunkiem o wartości przynajmniej " +
                        (user.getSemester().getField2Ects() - courseService.countEcts(common)) + " ects.");
            }
        }
    }

    public void checkCompulsoryCourses(User user) {
        Set<Course> basket = user.getCourseBasket();
        Set<Course> newBasket = new HashSet<>(basket);
        if (user.getSemester().getCompulsorySubjects() != null) {
            String[] compulsory = user.getSemester().getCompulsorySubjects();
            for (String subject : compulsory) {
                if (!newBasket.stream()
                        .filter(x -> x.getSubject().getSignature().equals(subject)).findFirst().isPresent()) {
                    System.out.println("W koszyku brakuje przedmiotu o sygnaturze " + subject);

                }
            }
        }
    }

    @Override
    public void validate(String login) {
        User user = findByEmail(login);
        checkEcts(user);
        checkFieldCourses(user, true);
        checkFieldCourses(user, false);
        checkCompulsoryCourses(user);
        checkComplexCourses(user);

//        Set<Course> b1 = basket.stream()
//        Set<Course> b2;
//        for (Course course : basket) {
//            if (course.getSubject().isComplex())
//        }

   /*     Map<Tuple, List<BlogPost>> postsPerTypeAndAuthor = posts.stream()
  .collect(groupingBy(post -> new Tuple(post.getType(), post.getAuthor()))); */

//        Map<String, Set<Course>> coursePerSignature = basket.stream().collect(
//                Collectors.groupingBy(Course::getSubject        ));


//        Map<String, List<Course>> courses = basket.stream()
//                .collect(Collectors.groupingBy(course -> course.getSubject().getSignature()));
//
//        for (String signature : courses.keySet()) {
//            List<Course> coursesPerSignature = courses.get(signature);
//            long number = coursesPerSignature.stream()
//                    .filter(course -> course.getSubject().isComplex() == true).count();
//            if(number != 2L) {
//                throw new RuntimeException("Do przedmiotu o sygnaturze" + signature + "brakuje wykładu, ćwiczeń");
//            }
//        }



    }
}


